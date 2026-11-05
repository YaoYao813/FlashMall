package com.example.seckillservice.service.impl;

import com.example.flashmall.common.entity.ProductStock;
import com.example.flashmall.common.entity.SeckillEvent;
import com.example.flashmall.common.mapper.ProductStockMapper;
import com.example.flashmall.common.mapper.SeckillEventMapper;
import com.example.seckillservice.cache.SeckillEventCache;
import com.example.seckillservice.dto.StockSyncRequest;
import com.example.seckillservice.vo.StockSyncVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StockSyncServiceImplTest {

    private SeckillEventMapper mapper;
    private SeckillEventCache cache;
    private ProductStockMapper productStockMapper;
    private RedissonClient redissonClient;
    private RLock lock;
    private StockSyncServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        mapper = mock(SeckillEventMapper.class);
        cache = mock(SeckillEventCache.class);
        productStockMapper = mock(ProductStockMapper.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        lenient().when(redissonClient.getLock(anyString())).thenReturn(lock);
        lenient().when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        lenient().when(lock.isHeldByCurrentThread()).thenReturn(true);
        service = new StockSyncServiceImpl(mapper, cache, productStockMapper, redissonClient);
    }

    // ==================== reset ====================

    @Test
    void resetWritesStockAndWindowForPendingEvent() {
        SeckillEvent event = pendingEvent();
        when(mapper.selectById(9L)).thenReturn(event);
        when(productStockMapper.selectByProductId(7L)).thenReturn(warehouse(50));
        when(cache.getStock(7L)).thenReturn(50);

        StockSyncVO vo = service.syncStock(9L, request("reset", null));

        assertEquals(50, vo.getDbStock());
        assertEquals(50, vo.getRedisStock());
        assertEquals("reset", vo.getMode());
        verify(cache).preheat(event);
    }

    @Test
    void resetRejectsWhenWarehouseStockInsufficient() {
        SeckillEvent event = pendingEvent();
        when(mapper.selectById(9L)).thenReturn(event);
        when(productStockMapper.selectByProductId(7L)).thenReturn(warehouse(30));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.syncStock(9L, request("reset", null)));

        assertEquals("仓库库存不足，无法预热：活动库存 50，仓库剩余 30", e.getMessage());
        verifyNoInteractions(cache);
    }

    @Test
    void resetRejectsWhenWarehouseRowMissing() {
        when(mapper.selectById(9L)).thenReturn(pendingEvent());
        when(productStockMapper.selectByProductId(7L)).thenReturn(null);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.syncStock(9L, request("reset", null)));

        assertEquals("仓库库存不足，无法预热：活动库存 50，仓库剩余 0", e.getMessage());
        verifyNoInteractions(cache);
    }

    @Test
    void resetRejectsRunningEvent() {
        when(mapper.selectById(9L)).thenReturn(runningEvent());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.syncStock(9L, request("reset", null)));

        assertEquals("只有未开始的活动可以预热", e.getMessage());
        verifyNoInteractions(cache);
    }

    @Test
    void resetRechecksStatusInsideLock() {
        SeckillEvent event = pendingEvent();
        when(mapper.selectById(9L)).thenReturn(event, runningEvent());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.syncStock(9L, request("reset", null)));

        assertEquals("只有未开始的活动可以预热", e.getMessage());
        verify(cache, never()).preheat(any());
    }

    // ==================== increment ====================

    @Test
    void incrementAddsDeltaToDbAndRedis() {
        SeckillEvent running = runningEvent();
        SeckillEvent updated = runningEvent();
        updated.setStock(60);
        when(mapper.selectById(9L)).thenReturn(running, running, updated);
        when(cache.getStock(7L)).thenReturn(40, 50);
        when(cache.incrementStock(7L, 10)).thenReturn(50L);
        when(mapper.incrementStock(9L, 10)).thenReturn(1);

        StockSyncVO vo = service.syncStock(9L, request("increment", 10));

        assertEquals(60, vo.getDbStock());
        assertEquals(50, vo.getRedisStock());
        assertEquals("increment", vo.getMode());
        verify(cache).incrementStock(7L, 10);
        verify(mapper).incrementStock(9L, 10);
    }

    @Test
    void incrementRejectsZeroDelta() {
        when(mapper.selectById(9L)).thenReturn(runningEvent());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.syncStock(9L, request("increment", 0)));

        assertEquals("库存调整量不能为 0", e.getMessage());
        verifyNoInteractions(redissonClient, cache);
    }

    @Test
    void incrementRejectsPendingEvent() {
        when(mapper.selectById(9L)).thenReturn(pendingEvent());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.syncStock(9L, request("increment", 10)));

        assertEquals("只有进行中的活动可以调整库存", e.getMessage());
        verifyNoInteractions(cache);
    }

    @Test
    void incrementRejectsWhenNotPreheated() {
        when(mapper.selectById(9L)).thenReturn(runningEvent());
        when(cache.getStock(7L)).thenReturn(null);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.syncStock(9L, request("increment", 10)));

        assertEquals("活动未预热，请先预热", e.getMessage());
        verify(cache, never()).incrementStock(anyLong(), anyLong());
        verify(mapper, never()).incrementStock(anyLong(), anyInt());
    }

    @Test
    void incrementRejectsWhenRedisWouldGoNegative() {
        when(mapper.selectById(9L)).thenReturn(runningEvent());
        when(cache.getStock(7L)).thenReturn(5);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.syncStock(9L, request("increment", -10)));

        assertEquals("调整后库存不能为负数", e.getMessage());
        verify(cache, never()).incrementStock(anyLong(), anyLong());
        verify(mapper, never()).incrementStock(anyLong(), anyInt());
    }

    @Test
    void incrementCompensatesWhenRedisRaceMakesResultNegative() {
        when(mapper.selectById(9L)).thenReturn(runningEvent());
        // 预检时还有 10，足够减 10；Lua 在 INCRBY 前又扣了 1，结果变 -1
        when(cache.getStock(7L)).thenReturn(10);
        when(cache.incrementStock(7L, -10)).thenReturn(-1L);
        when(cache.incrementStock(7L, 10L)).thenReturn(0L);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.syncStock(9L, request("increment", -10)));

        assertEquals("调整后库存不能为负数", e.getMessage());
        verify(cache).incrementStock(7L, -10);
        verify(cache).incrementStock(7L, 10L);
        verify(mapper, never()).incrementStock(anyLong(), anyInt());
    }

    @Test
    void incrementCompensatesWhenDbUpdateRejected() {
        when(mapper.selectById(9L)).thenReturn(runningEvent());
        when(cache.getStock(7L)).thenReturn(40);
        when(cache.incrementStock(7L, 10)).thenReturn(50L);
        when(cache.incrementStock(7L, -10L)).thenReturn(40L);
        when(mapper.incrementStock(9L, 10)).thenReturn(0);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.syncStock(9L, request("increment", 10)));

        assertEquals("调整后库存不能为负数", e.getMessage());
        verify(cache).incrementStock(7L, -10L);
    }

    @Test
    void incrementRejectsWhenLockBusy() throws Exception {
        when(mapper.selectById(9L)).thenReturn(runningEvent());
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.syncStock(9L, request("increment", 10)));

        assertEquals("库存调整正忙，请稍后重试", e.getMessage());
        verifyNoInteractions(cache);
    }

    // ==================== 通用 ====================

    @Test
    void rejectsMissingEvent() {
        when(mapper.selectById(9L)).thenReturn(null);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.syncStock(9L, request("reset", null)));

        assertEquals("活动不存在", e.getMessage());
    }

    @Test
    void rejectsUnknownMode() {
        when(mapper.selectById(9L)).thenReturn(pendingEvent());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.syncStock(9L, request("update", null)));

        assertEquals("mode 只能是 reset 或 increment", e.getMessage());
    }

    private StockSyncRequest request(String mode, Integer delta) {
        StockSyncRequest request = new StockSyncRequest();
        request.setMode(mode);
        request.setDelta(delta);
        return request;
    }

    private ProductStock warehouse(int stock) {
        ProductStock productStock = new ProductStock();
        productStock.setProductId(7L);
        productStock.setStock(stock);
        return productStock;
    }

    private SeckillEvent pendingEvent() {
        LocalDateTime now = LocalDateTime.now();
        return event(now.plusDays(1), now.plusDays(1).plusHours(2));
    }

    private SeckillEvent runningEvent() {
        LocalDateTime now = LocalDateTime.now();
        return event(now.minusHours(1), now.plusHours(1));
    }

    private SeckillEvent event(LocalDateTime start, LocalDateTime end) {
        SeckillEvent event = new SeckillEvent();
        event.setId(9L);
        event.setProductId(7L);
        event.setStock(50);
        event.setStartTime(start);
        event.setEndTime(end);
        return event;
    }
}
