package com.example.seckillservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.flashmall.common.entity.Order;
import com.example.flashmall.common.entity.ProductStock;
import com.example.flashmall.common.entity.SeckillEvent;
import com.example.flashmall.common.mapper.OrderMapper;
import com.example.flashmall.common.mapper.ProductStockMapper;
import com.example.flashmall.common.mapper.SeckillEventMapper;
import com.example.seckillservice.cache.SeckillEventCache;
import com.example.seckillservice.config.RabbitMQConfig;
import com.example.seckillservice.enums.EventStatus;
import com.example.seckillservice.service.StockReconcileService;
import com.example.seckillservice.vo.ReconcileVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 库存对账实现。
 *
 * <p>判定逻辑（两侧对称）：
 * <pre>
 *   gap = 应有消耗 - 实际消耗
 *   gap == 0            → OK
 *   gap &lt; 0            → DRIFT（扣得比订单多，正常链路产生不了负数）
 *   0 &lt; gap &lt;= 在途     → PENDING（队列里还有没消费完的消息）
 *   gap &gt; 在途         → DRIFT
 * </pre>
 *
 * <p>「在途」只统计 ready 消息，不含正在处理（已投递未 ack）的那一批，
 * 所以瞬时可能出现假 DRIFT。告警因此要求**连续两次扫描**都是 DRIFT 才升级为 ERROR。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockReconcileServiceImpl implements StockReconcileService {

    /** OrderStatus：0=待支付, 1=已支付, 2=已发货, 3=已完成, 4=已取消, 5=超时 */
    private static final List<Integer> CANCELLED_STATUSES =
            Collections.unmodifiableList(Arrays.asList(4, 5));

    static final String VERDICT_OK = "OK";
    static final String VERDICT_PENDING = "PENDING";
    static final String VERDICT_DRIFT = "DRIFT";
    static final String VERDICT_UNKNOWN = "UNKNOWN";
    /** 同一商品已有更新的场次，Redis 与仓库都已被后来的活动改写，本场无从判定 */
    static final String VERDICT_STALE = "STALE";

    static final String FINGERPRINT_NONE = "NONE";
    static final String FINGERPRINT_DUPLICATE_ROLLBACK = "DUPLICATE_ROLLBACK";
    static final String FINGERPRINT_REDIS_OVER_ROLLBACK = "REDIS_OVER_ROLLBACK";
    static final String FINGERPRINT_WAREHOUSE_OVER_ROLLBACK = "WAREHOUSE_OVER_ROLLBACK";
    static final String FINGERPRINT_ROLLBACK_MISSED = "ROLLBACK_MISSED";
    static final String FINGERPRINT_MIXED_SIGN = "MIXED_SIGN";

    /** 活动结束后仍然纳入扫描的分钟数：给队列排空和超时取消留时间 */
    private static final int RECENT_MINUTES = 30;
    /** 连续多少次扫描都是 DRIFT 才升级为 ERROR */
    private static final int ALERT_STREAK = 2;

    /** 告警行是单行 JSON，方便日志系统按字段检索；序列化失败时退化为 key=value */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SeckillEventMapper seckillEventMapper;
    private final OrderMapper orderMapper;
    private final ProductStockMapper productStockMapper;
    private final SeckillEventCache seckillEventCache;
    private final RabbitTemplate rabbitTemplate;

    /** eventId → 连续 DRIFT 次数。只在内存里，重启后重新计数 */
    private final Map<Long, Integer> driftStreak = new ConcurrentHashMap<>();

    @Override
    public ReconcileVO reconcile(Long eventId) {
        if (eventId == null) {
            throw new RuntimeException("活动ID不能为空");
        }
        SeckillEvent event = seckillEventMapper.selectById(eventId);
        if (event == null) {
            throw new RuntimeException("活动不存在");
        }
        return build(event, backlog());
    }

    @Override
    public List<ReconcileVO> reconcileRecent() {
        int backlog = backlog();
        List<SeckillEvent> events = seckillEventMapper.selectList(
                new LambdaQueryWrapper<SeckillEvent>()
                        .ge(SeckillEvent::getEndTime, LocalDateTime.now().minusMinutes(RECENT_MINUTES))
                        .orderByDesc(SeckillEvent::getId));
        List<ReconcileVO> results = new ArrayList<>(events.size());
        for (SeckillEvent event : events) {
            ReconcileVO vo = build(event, backlog);
            if (!VERDICT_STALE.equals(vo.getVerdict())) {
                results.add(vo);
            }
        }
        return results;
    }

    /**
     * 定时扫描。异常一律吞掉记日志：对账是旁路，不能把主服务拖垮。
     */
    @Scheduled(fixedDelayString = "${seckill.reconcile.interval-ms:60000}",
            initialDelayString = "${seckill.reconcile.initial-delay-ms:30000}")
    public void sweep() {
        List<SeckillEvent> events;
        try {
            events = seckillEventMapper.selectList(new LambdaQueryWrapper<SeckillEvent>()
                    .ge(SeckillEvent::getEndTime, LocalDateTime.now().minusMinutes(RECENT_MINUTES)));
        } catch (RuntimeException e) {
            log.warn("对账扫描跳过：读取活动列表失败", e);
            return;
        }
        if (events.isEmpty()) {
            return;
        }

        int backlog = backlog();
        for (SeckillEvent event : events) {
            try {
                alert(build(event, backlog));
            } catch (RuntimeException e) {
                log.error("对账失败：eventId={}", event.getId(), e);
            }
        }
    }

    // ==================== 判定 ====================

    private ReconcileVO build(SeckillEvent event, int backlog) {
        int orderCount = countOrders(event, null);
        int cancelledCount = countOrders(event, CANCELLED_STATUSES);
        // 净消耗：卖出去的减去退回来的
        int consumed = orderCount - cancelledCount;

        Integer redisStock = readRedisStock(event.getProductId());
        ReconcileVO.Quota quota = buildQuota(event.getStock(), redisStock, orderCount, cancelledCount,
                consumed, backlog);

        Integer baseline = event.getWarehouseBaseline();
        Integer dbStock = readWarehouseStock(event.getProductId());
        ReconcileVO.Warehouse warehouse = buildWarehouse(baseline, dbStock, consumed, backlog);

        // Redis 库存键与 product_stock 都是按商品维护的：同一商品一旦有更新的场次，
        // 旧场次的两侧读数都被改写过，再判 OK/DRIFT 只会制造噪声。
        boolean stale = seckillEventMapper.countNewerEvents(
                event.getProductId(), event.getId()) > 0;
        String verdict = stale
                ? VERDICT_STALE : merge(quota.getVerdict(), warehouse.getVerdict());
        if (stale) {
            quota.setVerdict(VERDICT_STALE);
            warehouse.setVerdict(VERDICT_STALE);
        }

        return ReconcileVO.builder()
                .eventId(event.getId())
                .productId(event.getProductId())
                .status(EventStatus.of(event.getStartTime(), event.getEndTime(),
                        LocalDateTime.now()).getCode())
                .verdict(verdict)
                .fingerprint(fingerprint(verdict, quota.getGap(), warehouse.getGap()))
                .backlog(backlog)
                .quota(quota)
                .warehouse(warehouse)
                .build();
    }

    private ReconcileVO.Quota buildQuota(Integer total, Integer redisStock, int orderCount,
                                         int cancelledCount, int consumed, int backlog) {
        Integer expectedRedis = null;
        Integer gap = null;
        String verdict;
        if (total == null || redisStock == null) {
            verdict = VERDICT_UNKNOWN;
        } else {
            expectedRedis = total - consumed;
            gap = (total - redisStock) - consumed;
            verdict = judge(gap, backlog);
        }
        return ReconcileVO.Quota.builder()
                .total(total)
                .redisStock(redisStock)
                .orderCount(orderCount)
                .cancelledCount(cancelledCount)
                .expectedRedis(expectedRedis)
                .gap(gap)
                .verdict(verdict)
                .build();
    }

    private ReconcileVO.Warehouse buildWarehouse(Integer baseline, Integer dbStock,
                                                 int consumed, int backlog) {
        Integer expectedDb = null;
        Integer gap = null;
        String verdict;
        if (baseline == null || dbStock == null) {
            // 基线缺失 = 这个活动是在对账上线之前预热的，只能判「未知」
            verdict = VERDICT_UNKNOWN;
        } else {
            expectedDb = baseline - consumed;
            gap = (baseline - dbStock) - consumed;
            verdict = judge(gap, backlog);
        }
        return ReconcileVO.Warehouse.builder()
                .baseline(baseline)
                .dbStock(dbStock)
                .expectedDb(expectedDb)
                .gap(gap)
                .verdict(verdict)
                .build();
    }

    private String judge(int gap, int backlog) {
        if (gap == 0) {
            return VERDICT_OK;
        }
        if (gap < 0) {
            return VERDICT_DRIFT;
        }
        return gap <= backlog ? VERDICT_PENDING : VERDICT_DRIFT;
    }

    /**
     * 两侧合并：任一侧 DRIFT 就是 DRIFT；否则任一侧 UNKNOWN 就是 UNKNOWN；
     * 否则任一侧 PENDING 就是 PENDING；全 OK 才 OK。
     */
    private String merge(String quotaVerdict, String warehouseVerdict) {
        if (VERDICT_DRIFT.equals(quotaVerdict) || VERDICT_DRIFT.equals(warehouseVerdict)) {
            return VERDICT_DRIFT;
        }
        if (VERDICT_UNKNOWN.equals(quotaVerdict) || VERDICT_UNKNOWN.equals(warehouseVerdict)) {
            return VERDICT_UNKNOWN;
        }
        if (VERDICT_PENDING.equals(quotaVerdict) || VERDICT_PENDING.equals(warehouseVerdict)) {
            return VERDICT_PENDING;
        }
        return VERDICT_OK;
    }

    /**
     * 从两侧 gap 的符号猜一个「可能的成因」。只用于告警定位和运营解释，
     * 不参与判定，也绝不据此自动修复。
     *
     * <p>gap 的符号语义：负 = 库存比应有值多（多退，超卖风险）；
     * 正 = 库存比应有值少（少退，或库存扣了但订单没落库）。
     */
    String fingerprint(String verdict, Integer quotaGap, Integer warehouseGap) {
        if (!VERDICT_DRIFT.equals(verdict)) {
            return FINGERPRINT_NONE;
        }
        boolean quotaNegative = quotaGap != null && quotaGap < 0;
        boolean warehouseNegative = warehouseGap != null && warehouseGap < 0;
        boolean quotaPositive = quotaGap != null && quotaGap > 0;
        boolean warehousePositive = warehouseGap != null && warehouseGap > 0;

        if (quotaNegative && warehouseNegative) {
            // 两侧同时多出同样的数量，最常见的原因是同一条回滚消息被执行了两次
            return FINGERPRINT_DUPLICATE_ROLLBACK;
        }
        if ((quotaNegative && warehousePositive) || (quotaPositive && warehouseNegative)) {
            // 方向相反说明不是一个原因造成的，硬归因会误导排查
            return FINGERPRINT_MIXED_SIGN;
        }
        if (quotaNegative) {
            return FINGERPRINT_REDIS_OVER_ROLLBACK;
        }
        if (warehouseNegative) {
            return FINGERPRINT_WAREHOUSE_OVER_ROLLBACK;
        }
        // 没有负值又是 DRIFT：至少一侧 gap > 0
        return FINGERPRINT_ROLLBACK_MISSED;
    }

    // ==================== 告警 ====================

    private void alert(ReconcileVO vo) {
        Long eventId = vo.getEventId();
        if (!VERDICT_DRIFT.equals(vo.getVerdict())) {
            driftStreak.remove(eventId);
            return;
        }
        int streak = driftStreak.merge(eventId, 1, Integer::sum);
        String line = alertLine(vo, streak);
        if (streak < ALERT_STREAK) {
            log.warn("reconcile.alert {}", line);
            return;
        }
        log.error("reconcile.alert {}", line);
    }

    /**
     * 单行 JSON。字段名与 {@code GET /reconcile} 的返回保持同名同义，
     * 方便从日志直接对照接口结果，也方便按 fingerprint 做聚合告警。
     *
     * <p>未知的读数一律是 {@code null}，不要读成 0（顶层 null 字段会被省略，
     * quota / warehouse 内部的 null 显式输出）。
     */
    static String alertLine(ReconcileVO vo, int streak) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("level", streak < ALERT_STREAK ? "SUSPECT" : "ALERT");
        payload.put("eventId", vo.getEventId());
        payload.put("productId", vo.getProductId());
        payload.put("status", vo.getStatus());
        payload.put("verdict", vo.getVerdict());
        payload.put("fingerprint", vo.getFingerprint());
        payload.put("streak", streak);
        payload.put("backlog", vo.getBacklog());
        payload.put("quota", vo.getQuota());
        payload.put("warehouse", vo.getWarehouse());
        try {
            return JSON.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // 序列化失败不能影响扫描继续跑：退化成一行 key=value，至少不丢告警
            return payload.toString();
        }
    }

    // ==================== 读数 ====================

    /**
     * 订单统计窗口：product_id + create_time 落在活动区间，与统计接口口径一致。
     * 边界副作用：end_time 之后才落库的订单不计入，这类偏差会体现为 DRIFT。
     */
    private int countOrders(SeckillEvent event, List<Integer> statuses) {
        LambdaQueryWrapper<Order> query = new LambdaQueryWrapper<Order>()
                .eq(Order::getProductId, event.getProductId())
                .ge(Order::getCreateTime, event.getStartTime())
                .le(Order::getCreateTime, event.getEndTime());
        if (statuses != null) {
            query.in(Order::getStatus, statuses);
        }
        Long count = orderMapper.selectCount(query);
        return count == null ? 0 : Math.toIntExact(count);
    }

    private Integer readRedisStock(Long productId) {
        try {
            return seckillEventCache.getStock(productId);
        } catch (DataAccessException e) {
            log.warn("对账读取 Redis 库存失败：productId={}", productId, e);
            return null;
        }
    }

    private Integer readWarehouseStock(Long productId) {
        ProductStock stock = productStockMapper.selectByProductId(productId);
        return stock == null ? null : stock.getStock();
    }

    /**
     * 在途容差：主队列 + 回滚队列的 ready 消息数。
     * 读不到（RabbitMQ 不可达）按 0 处理，宁可多报一次 DRIFT 也不漏报。
     */
    private int backlog() {
        return queueDepth(RabbitMQConfig.SECKILL_ORDER_QUEUE)
                + queueDepth(RabbitMQConfig.SECKILL_STOCK_ROLLBACK_QUEUE);
    }

    private int queueDepth(String queue) {
        try {
            Long count = rabbitTemplate.execute(channel -> channel.messageCount(queue));
            return count == null ? 0 : Math.toIntExact(count);
        } catch (RuntimeException e) {
            log.warn("对账读取队列深度失败：queue={}", queue, e);
            return 0;
        }
    }
}
