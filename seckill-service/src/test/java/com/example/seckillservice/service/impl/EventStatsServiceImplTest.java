package com.example.seckillservice.service.impl;

import com.example.flashmall.common.entity.ProductStock;
import com.example.flashmall.common.entity.SeckillEvent;
import com.example.flashmall.common.mapper.OrderMapper;
import com.example.flashmall.common.mapper.ProductStockMapper;
import com.example.flashmall.common.mapper.SeckillEventMapper;
import com.example.seckillservice.cache.SeckillEventCache;
import com.example.seckillservice.vo.EventStatsVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EventStatsServiceImplTest {

    private SeckillEventMapper eventMapper;
    private OrderMapper orderMapper;
    private ProductStockMapper productStockMapper;
    private SeckillEventCache cache;
    private EventStatsServiceImpl service;

    @BeforeEach
    void setUp() {
        eventMapper = mock(SeckillEventMapper.class);
        orderMapper = mock(OrderMapper.class);
        productStockMapper = mock(ProductStockMapper.class);
        cache = mock(SeckillEventCache.class);
        service = new EventStatsServiceImpl(eventMapper, orderMapper, productStockMapper, cache);
    }

    @Test
    void computesCountsGmvAndRates() {
        stubs(runningEvent(10, new BigDecimal("9.90")));
        when(orderMapper.selectCount(any())).thenReturn(3L, 1L, 1L);
        when(cache.getStock(7L)).thenReturn(7);
        when(productStockMapper.selectByProductId(7L)).thenReturn(warehouse(7));

        EventStatsVO vo = service.stats(9L);

        assertEquals(3, vo.getStats().getOrderCount());
        assertEquals(1, vo.getStats().getPaidCount());
        assertEquals(new BigDecimal("9.90"), vo.getStats().getGmv());
        assertEquals(new BigDecimal("0.3333"), vo.getStats().getPayRate());
        assertEquals(10, vo.getStats().getStockTotal());
        assertEquals(3, vo.getStats().getStockConsumed());
        assertEquals(new BigDecimal("0.3000"), vo.getStats().getConsumeRate());
        assertEquals(7, vo.getStock().getRedisStock());
        assertEquals(7, vo.getStock().getDbStock());
        assertEquals(1, vo.getStock().getOrdersOutstanding());
        assertEquals("running", vo.getEvent().getStatus());
        assertEquals(new BigDecimal("9.90"), vo.getEvent().getSeckillPrice());
    }

    @Test
    void zeroOrdersYieldsZeroRatesAndGmv() {
        stubs(runningEvent(10, new BigDecimal("9.90")));
        when(orderMapper.selectCount(any())).thenReturn(0L, 0L, 0L);
        when(cache.getStock(7L)).thenReturn(10);
        when(productStockMapper.selectByProductId(7L)).thenReturn(warehouse(10));

        EventStatsVO vo = service.stats(9L);

        assertEquals(new BigDecimal("0.00"), vo.getStats().getGmv());
        assertEquals(new BigDecimal("0.0000"), vo.getStats().getPayRate());
        assertEquals(0, vo.getStats().getStockConsumed());
        assertEquals(new BigDecimal("0.0000"), vo.getStats().getConsumeRate());
    }

    @Test
    void redisUnavailableReturnsNullStockInsteadOfZero() {
        stubs(runningEvent(10, new BigDecimal("9.90")));
        when(orderMapper.selectCount(any())).thenReturn(3L, 1L, 1L);
        when(cache.getStock(7L)).thenThrow(new RedisConnectionFailureException("down"));
        when(productStockMapper.selectByProductId(7L)).thenReturn(warehouse(7));

        EventStatsVO vo = service.stats(9L);

        assertNull(vo.getStock().getRedisStock());
        assertNull(vo.getStats().getStockConsumed());
        assertNull(vo.getStats().getConsumeRate());
        // 仓库和订单维度仍可返回
        assertEquals(7, vo.getStock().getDbStock());
        assertEquals(3, vo.getStats().getOrderCount());
    }

    @Test
    void missingRedisKeyAlsoReturnsNull() {
        stubs(runningEvent(10, new BigDecimal("9.90")));
        when(orderMapper.selectCount(any())).thenReturn(0L, 0L, 0L);
        when(cache.getStock(7L)).thenReturn(null);

        EventStatsVO vo = service.stats(9L);

        assertNull(vo.getStock().getRedisStock());
        assertNull(vo.getStats().getStockConsumed());
        assertNull(vo.getStats().getConsumeRate());
    }

    @Test
    void zeroTotalStockMakesConsumeRateNull() {
        stubs(runningEvent(0, new BigDecimal("9.90")));
        when(orderMapper.selectCount(any())).thenReturn(0L, 0L, 0L);
        when(cache.getStock(7L)).thenReturn(0);
        when(productStockMapper.selectByProductId(7L)).thenReturn(warehouse(0));

        EventStatsVO vo = service.stats(9L);

        assertEquals(0, vo.getStats().getStockConsumed());
        assertNull(vo.getStats().getConsumeRate());
    }

    @Test
    void missingWarehouseRowLeavesDbStockNull() {
        stubs(runningEvent(10, new BigDecimal("9.90")));
        when(orderMapper.selectCount(any())).thenReturn(0L, 0L, 0L);
        when(cache.getStock(7L)).thenReturn(10);
        when(productStockMapper.selectByProductId(7L)).thenReturn(null);

        EventStatsVO vo = service.stats(9L);

        assertNull(vo.getStock().getDbStock());
    }

    @Test
    void derivesPendingAndEndedStatus() {
        SeckillEvent pending = runningEvent(10, new BigDecimal("9.90"));
        LocalDateTime now = LocalDateTime.now();
        pending.setStartTime(now.plusDays(1));
        pending.setEndTime(now.plusDays(1).plusHours(2));
        when(eventMapper.selectById(9L)).thenReturn(pending);
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(cache.getStock(7L)).thenReturn(10);
        when(productStockMapper.selectByProductId(7L)).thenReturn(warehouse(10));

        assertEquals("pending", service.stats(9L).getEvent().getStatus());

        SeckillEvent ended = runningEvent(10, new BigDecimal("9.90"));
        ended.setStartTime(now.minusDays(1));
        ended.setEndTime(now.minusHours(1));
        when(eventMapper.selectById(9L)).thenReturn(ended);

        assertEquals("ended", service.stats(9L).getEvent().getStatus());
    }

    @Test
    void rejectsMissingEvent() {
        when(eventMapper.selectById(9L)).thenReturn(null);

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.stats(9L));

        assertEquals("活动不存在", e.getMessage());
        verifyNoInteractions(orderMapper, cache, productStockMapper);
    }

    @Test
    void rejectsNullEventId() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.stats(null));

        assertEquals("活动ID不能为空", e.getMessage());
        verifyNoInteractions(eventMapper);
    }

    @Test
    void countsOrdersWithinEventWindow() {
        SeckillEvent event = runningEvent(10, new BigDecimal("9.90"));
        stubs(event);
        when(orderMapper.selectCount(any())).thenReturn(3L, 1L, 1L);
        when(cache.getStock(anyLong())).thenReturn(7);

        service.stats(9L);

        // 三次计数：全部 / 已支付 / 待支付
        verify(orderMapper, org.mockito.Mockito.times(3)).selectCount(any());
    }

    private void stubs(SeckillEvent event) {
        when(eventMapper.selectById(9L)).thenReturn(event);
    }

    private SeckillEvent runningEvent(int stock, BigDecimal price) {
        LocalDateTime now = LocalDateTime.now();
        SeckillEvent event = new SeckillEvent();
        event.setId(9L);
        event.setProductId(7L);
        event.setStock(stock);
        event.setSeckillPrice(price);
        event.setStartTime(now.minusHours(1));
        event.setEndTime(now.plusHours(1));
        return event;
    }

    private ProductStock warehouse(int stock) {
        ProductStock productStock = new ProductStock();
        productStock.setProductId(7L);
        productStock.setStock(stock);
        return productStock;
    }
}
