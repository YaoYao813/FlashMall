package com.example.seckillservice.service.impl;

import com.example.flashmall.common.entity.AdminIdempotency;
import com.example.flashmall.common.entity.ProductStock;
import com.example.flashmall.common.entity.SeckillEvent;
import com.example.flashmall.common.mapper.AdminIdempotencyMapper;
import com.example.flashmall.common.mapper.ProductStockMapper;
import com.example.flashmall.common.mapper.SeckillEventMapper;
import com.example.seckillservice.cache.SeckillEventCache;
import com.example.seckillservice.dto.StockSyncRequest;
import com.example.seckillservice.service.AdminAuditService;
import com.example.seckillservice.vo.StockSyncVO;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    private AdminIdempotencyMapper idempotencyMapper;
    private AdminAuditService adminAuditService;
    private ObjectMapper objectMapper;
    private StockSyncServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        mapper = mock(SeckillEventMapper.class);
        cache = mock(SeckillEventCache.class);
        productStockMapper = mock(ProductStockMapper.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        idempotencyMapper = mock(AdminIdempotencyMapper.class);
        adminAuditService = mock(AdminAuditService.class);
        // 与 Spring Boot 默认一致：忽略未知字段，便于识别「键被别的工具用过」
        objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        lenient().when(redissonClient.getLock(anyString())).thenReturn(lock);
        lenient().when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        lenient().when(lock.isHeldByCurrentThread()).thenReturn(true);
        lenient().when(idempotencyMapper.selectById(anyString())).thenReturn(null);
        service = new StockSyncServiceImpl(mapper, cache, productStockMapper, redissonClient,
                idempotencyMapper, objectMapper, adminAuditService);
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

    // ==================== 幂等 / 审计 ====================

    @Test
    void incrementWithKeyWritesIdempotencyAndAudit() {
        SeckillEvent running = runningEvent();
        SeckillEvent updated = runningEvent();
        updated.setStock(60);
        when(mapper.selectById(9L)).thenReturn(running, running, updated);
        when(cache.getStock(7L)).thenReturn(40, 50);
        when(cache.incrementStock(7L, 10)).thenReturn(50L);
        when(mapper.incrementStock(9L, 10)).thenReturn(1);

        StockSyncRequest request = request("increment", 10);
        request.setReason("活动热度高");
        StockSyncVO vo = service.syncStock(9L, request, "t:1", 3L, "adjust_seckill_stock");

        assertFalse(vo.getReplayed());
        ArgumentCaptor<AdminIdempotency> captor = ArgumentCaptor.forClass(AdminIdempotency.class);
        verify(idempotencyMapper).insert(captor.capture());
        assertEquals("t:1", captor.getValue().getIdempotencyKey());
        assertTrue(captor.getValue().getResponseJson().contains("\"mode\":\"increment\""));
        verify(adminAuditService).record(
                eq("adjust_seckill_stock"), eq("t:1"), eq(3L), any(), any());
    }

    @Test
    void incrementReplaysStoredResponseWithoutTouchingStock() throws Exception {
        StockSyncVO stored = StockSyncVO.builder()
                .dbStock(60).redisStock(50).mode("increment").replayed(false).build();
        AdminIdempotency record = new AdminIdempotency();
        record.setIdempotencyKey("t:1");
        record.setResponseJson(objectMapper.writeValueAsString(stored));
        when(idempotencyMapper.selectById("t:1")).thenReturn(record);
        when(mapper.selectById(9L)).thenReturn(runningEvent());

        StockSyncVO vo = service.syncStock(9L, request("increment", 10), "t:1", 3L, "adjust_seckill_stock");

        assertTrue(vo.getReplayed());
        assertEquals(60, vo.getDbStock());
        verifyNoInteractions(cache, adminAuditService);
        verify(mapper, never()).incrementStock(anyLong(), anyInt());
        verify(idempotencyMapper, never()).insert(any());
    }

    @Test
    void incrementWithoutKeySkipsIdempotency() {
        SeckillEvent running = runningEvent();
        SeckillEvent updated = runningEvent();
        updated.setStock(60);
        when(mapper.selectById(9L)).thenReturn(running, running, updated);
        when(cache.getStock(7L)).thenReturn(40, 50);
        when(cache.incrementStock(7L, 10)).thenReturn(50L);
        when(mapper.incrementStock(9L, 10)).thenReturn(1);

        service.syncStock(9L, request("increment", 10));

        verify(idempotencyMapper, never()).insert(any());
    }

    @Test
    void incrementRejectsKeyReusedByAnotherTool() throws Exception {
        AdminIdempotency record = new AdminIdempotency();
        record.setIdempotencyKey("t:1");
        record.setResponseJson("{\"id\":9,\"productId\":7}");
        when(idempotencyMapper.selectById("t:1")).thenReturn(record);
        when(mapper.selectById(9L)).thenReturn(runningEvent());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.syncStock(9L, request("increment", 10), "t:1", 3L, null));

        assertEquals("幂等键已被其他请求使用", e.getMessage());
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
