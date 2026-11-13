package com.example.seckillservice.service;

import com.example.flashmall.common.entity.ProductStock;
import com.example.flashmall.common.entity.SeckillEvent;
import com.example.flashmall.common.mapper.OrderMapper;
import com.example.flashmall.common.mapper.ProductStockMapper;
import com.example.flashmall.common.mapper.SeckillEventMapper;
import com.example.seckillservice.cache.SeckillEventCache;
import com.example.seckillservice.service.impl.StockReconcileServiceImpl;
import com.example.seckillservice.vo.ReconcileVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.core.ChannelCallback;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockReconcileServiceImplTest {

    private static final Long EVENT_ID = 100L;
    private static final Long PRODUCT_ID = 7L;

    @Mock
    private SeckillEventMapper seckillEventMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private ProductStockMapper productStockMapper;
    @Mock
    private SeckillEventCache seckillEventCache;
    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private StockReconcileServiceImpl service;

    private SeckillEvent event;

    @BeforeEach
    void setUp() {
        event = new SeckillEvent();
        event.setId(EVENT_ID);
        event.setProductId(PRODUCT_ID);
        event.setStock(10);
        event.setWarehouseBaseline(50);
        event.setStartTime(LocalDateTime.now().minusHours(1));
        event.setEndTime(LocalDateTime.now().plusHours(1));
        when(seckillEventMapper.selectById(EVENT_ID)).thenReturn(event);
        when(rabbitTemplate.execute(ArgumentMatchers.<ChannelCallback<Long>>any())).thenReturn(0L);
    }

    @Test
    @DisplayName("两侧都对得上：OK")
    void testAllMatched() {
        given(8, 48, 2, 0);

        ReconcileVO vo = service.reconcile(EVENT_ID);

        assertEquals("OK", vo.getVerdict());
        assertEquals("NONE", vo.getFingerprint());
        assertEquals(0, vo.getQuota().getGap());
        assertEquals(0, vo.getWarehouse().getGap());
        assertEquals(8, vo.getQuota().getExpectedRedis());
        assertEquals(48, vo.getWarehouse().getExpectedDb());
    }

    @Test
    @DisplayName("取消过的订单不计入净消耗")
    void testCancelledOrdersAreRefunded() {
        // 卖了 3 单、退了 1 单 → 净消耗 2，Redis 应从 10 掉到 8
        given(8, 48, 3, 1);

        ReconcileVO vo = service.reconcile(EVENT_ID);

        assertEquals("OK", vo.getVerdict());
        assertEquals(3, vo.getQuota().getOrderCount());
        assertEquals(1, vo.getQuota().getCancelledCount());
    }

    @Test
    @DisplayName("Redis 扣了但订单还在队列里：PENDING，不算异常")
    void testInFlightIsPending() {
        given(5, 48, 2, 0);
        // 主队列 3 条在途，回滚队列 0 条
        when(rabbitTemplate.execute(ArgumentMatchers.<ChannelCallback<Long>>any()))
                .thenReturn(3L, 0L);

        ReconcileVO vo = service.reconcile(EVENT_ID);

        assertEquals("PENDING", vo.getVerdict());
        assertEquals(3, vo.getQuota().getGap());
        assertEquals(3, vo.getBacklog());
    }

    @Test
    @DisplayName("Redis 比订单多扣且无在途解释：DRIFT")
    void testQuotaDrift() {
        given(5, 48, 2, 0);

        ReconcileVO vo = service.reconcile(EVENT_ID);

        assertEquals("DRIFT", vo.getVerdict());
        assertEquals("DRIFT", vo.getQuota().getVerdict());
        assertEquals("OK", vo.getWarehouse().getVerdict());
        // 正 gap = Redis 少退了（退单没有归还库存），不是多退
        assertEquals("ROLLBACK_MISSED", vo.getFingerprint());
    }

    @Test
    @DisplayName("仓库少了货但没有对应订单：WAREHOUSE DRIFT")
    void testWarehouseDrift() {
        given(8, 45, 2, 0);

        ReconcileVO vo = service.reconcile(EVENT_ID);

        assertEquals("DRIFT", vo.getVerdict());
        assertEquals("OK", vo.getQuota().getVerdict());
        assertEquals("DRIFT", vo.getWarehouse().getVerdict());
        assertEquals(3, vo.getWarehouse().getGap());
        assertEquals("ROLLBACK_MISSED", vo.getFingerprint());
    }

    @Test
    @DisplayName("Redis 库存比应有值还多：负 gap 直接判 DRIFT")
    void testNegativeGapIsDrift() {
        given(11, 50, 0, 0);

        ReconcileVO vo = service.reconcile(EVENT_ID);

        assertEquals("DRIFT", vo.getVerdict());
        assertEquals(-1, vo.getQuota().getGap());
        assertEquals("REDIS_OVER_ROLLBACK", vo.getFingerprint());
    }

    @Test
    @DisplayName("只有仓库侧多退：WAREHOUSE_OVER_ROLLBACK")
    void testWarehouseOverRollbackFingerprint() {
        given(8, 49, 2, 0);

        ReconcileVO vo = service.reconcile(EVENT_ID);

        assertEquals("DRIFT", vo.getVerdict());
        assertEquals(-1, vo.getWarehouse().getGap());
        assertEquals("WAREHOUSE_OVER_ROLLBACK", vo.getFingerprint());
    }

    @Test
    @DisplayName("两侧同时多退：DUPLICATE_ROLLBACK（同一条回滚消息执行了两次）")
    void testDuplicateRollbackFingerprint() {
        given(10, 50, 2, 1);

        ReconcileVO vo = service.reconcile(EVENT_ID);

        assertEquals("DRIFT", vo.getVerdict());
        assertTrue(vo.getQuota().getGap() < 0);
        assertTrue(vo.getWarehouse().getGap() < 0);
        assertEquals("DUPLICATE_ROLLBACK", vo.getFingerprint());
    }

    @Test
    @DisplayName("两侧方向相反：MIXED_SIGN，不硬归因")
    void testMixedSignFingerprint() {
        given(12, 46, 0, 0);

        ReconcileVO vo = service.reconcile(EVENT_ID);

        assertEquals("DRIFT", vo.getVerdict());
        assertTrue(vo.getQuota().getGap() < 0);
        assertTrue(vo.getWarehouse().getGap() > 0);
        assertEquals("MIXED_SIGN", vo.getFingerprint());
    }

    @Test
    @DisplayName("历史活动没有基线：仓库侧 UNKNOWN，不用 0 冒充")
    void testUnknownBaseline() {
        event.setWarehouseBaseline(null);
        given(8, 48, 2, 0);

        ReconcileVO vo = service.reconcile(EVENT_ID);

        assertEquals("UNKNOWN", vo.getVerdict());
        assertEquals("OK", vo.getQuota().getVerdict());
        assertEquals("UNKNOWN", vo.getWarehouse().getVerdict());
        assertNull(vo.getWarehouse().getGap());
    }

    @Test
    @DisplayName("Redis 不可达：配额侧 UNKNOWN，不用 0 冒充")
    void testRedisDown() {
        given(null, 48, 2, 0);

        ReconcileVO vo = service.reconcile(EVENT_ID);

        assertEquals("UNKNOWN", vo.getVerdict());
        assertNull(vo.getQuota().getGap());
        assertNull(vo.getQuota().getExpectedRedis());
    }

    @Test
    @DisplayName("同一商品已有更新的场次：判 STALE，不拿旧读数误报 DRIFT")
    void testStaleEvent() {
        when(seckillEventMapper.countNewerEvents(PRODUCT_ID, EVENT_ID)).thenReturn(1);
        // 数字本身会判 DRIFT，但旧场次的读数已被后来活动覆盖，不应下结论
        given(11, 50, 0, 0);

        ReconcileVO vo = service.reconcile(EVENT_ID);

        assertEquals("STALE", vo.getVerdict());
        assertEquals("STALE", vo.getQuota().getVerdict());
        assertEquals("STALE", vo.getWarehouse().getVerdict());
    }

    // ==================== helpers ====================

    private void given(Integer redisStock, Integer warehouseStock, long orderCount, long cancelled) {
        when(seckillEventCache.getStock(anyLong())).thenReturn(redisStock);
        ProductStock stock = new ProductStock();
        stock.setProductId(PRODUCT_ID);
        stock.setStock(warehouseStock);
        when(productStockMapper.selectByProductId(anyLong())).thenReturn(stock);
        when(orderMapper.selectCount(any())).thenReturn(orderCount, cancelled);
    }
}
