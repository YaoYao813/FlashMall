package com.example.seckillservice.service.impl;

import com.example.flashmall.common.entity.AdminIdempotency;
import com.example.flashmall.common.entity.ProductStock;
import com.example.flashmall.common.entity.SeckillEvent;
import com.example.flashmall.common.mapper.AdminIdempotencyMapper;
import com.example.flashmall.common.mapper.ProductStockMapper;
import com.example.flashmall.common.mapper.SeckillEventMapper;
import com.example.seckillservice.cache.SeckillEventCache;
import com.example.seckillservice.dto.StockSyncRequest;
import com.example.seckillservice.enums.EventStatus;
import com.example.seckillservice.service.AdminAuditService;
import com.example.seckillservice.service.StockSyncService;
import com.example.seckillservice.vo.StockSyncVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 库存同步实现。
 *
 * <p>并发约定：
 * <ul>
 *   <li>运营调整之间用 Redisson 锁串行；</li>
 *   <li>DB 用 {@code stock = stock + delta} 原子更新，避免读-改-写丢更新；</li>
 *   <li>秒杀扣减走 Lua，不拿这把锁，所以 Redis 用原子 INCRBY；
 *       若结果变负，说明调整与扣减交错，反向 INCRBY 补偿后拒绝。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockSyncServiceImpl implements StockSyncService {

    private static final String LOCK_PREFIX = "lock:seckill:admin:stock:";
    private static final long LOCK_WAIT_SECONDS = 3;
    private static final long LOCK_LEASE_SECONDS = 10;

    private static final String MODE_RESET = "reset";
    private static final String MODE_INCREMENT = "increment";

    private final SeckillEventMapper seckillEventMapper;
    private final SeckillEventCache seckillEventCache;
    private final ProductStockMapper productStockMapper;
    private final RedissonClient redissonClient;
    private final AdminIdempotencyMapper adminIdempotencyMapper;
    private final ObjectMapper objectMapper;
    private final AdminAuditService adminAuditService;

    @Override
    public StockSyncVO syncStock(Long eventId, StockSyncRequest request) {
        return syncStock(eventId, request, null, null, null);
    }

    @Override
    public StockSyncVO syncStock(Long eventId, StockSyncRequest request,
                                 String idempotencyKey, Long operatorId, String toolName) {
        if (eventId == null) {
            throw new RuntimeException("活动ID不能为空");
        }
        String mode = request == null || request.getMode() == null
                ? "" : request.getMode().trim().toLowerCase(Locale.ROOT);
        if (!MODE_RESET.equals(mode) && !MODE_INCREMENT.equals(mode)) {
            throw new RuntimeException("mode 只能是 reset 或 increment");
        }

        SeckillEvent event = seckillEventMapper.selectById(eventId);
        if (event == null) {
            throw new RuntimeException("活动不存在");
        }

        if (MODE_RESET.equals(mode)) {
            // reset 是 SET 语义，重复执行结果相同，不认幂等键
            return reset(event);
        }

        String key = normalizeKey(idempotencyKey);
        if (key != null) {
            StockSyncVO replayed = replay(key);
            if (replayed != null) {
                return replayed;
            }
        }
        return increment(event, request, key, operatorId, toolName);
    }

    private StockSyncVO reset(SeckillEvent event) {
        return withLock(event.getProductId(), () -> {
            SeckillEvent fresh = requireStatus(event.getId(), EventStatus.PENDING,
                    "只有未开始的活动可以预热");

            // 预热只写 Redis 与 seckill_event，不碰 product_stock。
            // 若同一商品上一场已卖出过货，仓库剩余可能小于活动配额，先拒绝，
            // 否则 Redis 会显示多于实际可售的量，用户抢到后会在消费者侧失败（消息无死信）。
            ProductStock warehouse = productStockMapper.selectByProductId(fresh.getProductId());
            int available = warehouse == null || warehouse.getStock() == null ? 0 : warehouse.getStock();
            if (fresh.getStock() > available) {
                throw new RuntimeException(String.format(
                        "仓库库存不足，无法预热：活动库存 %d，仓库剩余 %d", fresh.getStock(), available));
            }

            seckillEventCache.preheat(fresh);
            Integer redisStock = seckillEventCache.getStock(fresh.getProductId());
            if (redisStock == null) {
                throw new RuntimeException("库存同步失败，请稍后重试");
            }
            log.info("秒杀活动预热完成：eventId={}, productId={}, stock={}",
                    fresh.getId(), fresh.getProductId(), fresh.getStock());
            return StockSyncVO.builder()
                    .dbStock(fresh.getStock())
                    .redisStock(redisStock)
                    .mode(MODE_RESET)
                    .replayed(false)
                    .build();
        });
    }

    private StockSyncVO increment(SeckillEvent event, StockSyncRequest request,
                                  String key, Long operatorId, String toolName) {
        Integer delta = request == null ? null : request.getDelta();
        if (delta == null || delta == 0) {
            throw new RuntimeException("库存调整量不能为 0");
        }

        return withLock(event.getProductId(), () -> {
            SeckillEvent fresh = requireStatus(event.getId(), EventStatus.RUNNING,
                    "只有进行中的活动可以调整库存");
            Long productId = fresh.getProductId();

            // 拿到锁后再查一次：并发同键请求在排队等锁，先到的已经写了幂等记录
            if (key != null) {
                StockSyncVO replayed = replay(key);
                if (replayed != null) {
                    return replayed;
                }
            }

            Integer redisBefore = seckillEventCache.getStock(productId);
            if (redisBefore == null) {
                throw new RuntimeException("活动未预热，请先预热");
            }
            if ((long) redisBefore + delta < 0) {
                throw new RuntimeException("调整后库存不能为负数");
            }

            Long redisAfter = seckillEventCache.incrementStock(productId, delta);
            if (redisAfter == null || redisAfter < 0) {
                compensate(productId, delta);
                throw new RuntimeException("调整后库存不能为负数");
            }

            int rows;
            try {
                rows = seckillEventMapper.incrementStock(fresh.getId(), delta);
            } catch (DataAccessException e) {
                compensate(productId, delta);
                log.error("调整活动库存数据库异常：eventId={}, delta={}", fresh.getId(), delta, e);
                throw new RuntimeException("库存同步失败，请稍后重试");
            }
            if (rows != 1) {
                compensate(productId, delta);
                throw new RuntimeException("调整后库存不能为负数");
            }

            SeckillEvent updated = seckillEventMapper.selectById(fresh.getId());
            int dbStock = updated != null ? updated.getStock() : fresh.getStock() + delta;
            Integer redisStock = seckillEventCache.getStock(productId);
            if (redisStock == null) {
                redisStock = redisAfter.intValue();
            }
            log.info("秒杀活动库存调整完成：eventId={}, productId={}, delta={}, dbStock={}, redisStock={}",
                    fresh.getId(), productId, delta, dbStock, redisStock);
            StockSyncVO vo = StockSyncVO.builder()
                    .dbStock(dbStock)
                    .redisStock(redisStock)
                    .mode(MODE_INCREMENT)
                    .replayed(false)
                    .build();
            if (key != null) {
                saveIdempotency(key, vo);
            }
            adminAuditService.record(resolveToolName(toolName), key, operatorId,
                    auditParams(fresh.getId(), delta, request.getReason()), vo);
            return vo;
        });
    }

    /**
     * 加锁后重新读库并校验状态，避免排队等锁期间活动已经开始/结束。
     */
    private SeckillEvent requireStatus(Long eventId, EventStatus expected, String message) {
        SeckillEvent fresh = seckillEventMapper.selectById(eventId);
        if (fresh == null) {
            throw new RuntimeException("活动不存在");
        }
        EventStatus actual = EventStatus.of(
                fresh.getStartTime(), fresh.getEndTime(), LocalDateTime.now());
        if (actual != expected) {
            throw new RuntimeException(message);
        }
        return fresh;
    }

    private <T> T withLock(Long productId, Supplier<T> action) {
        RLock lock = redissonClient.getLock(LOCK_PREFIX + productId);
        boolean locked = false;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                throw new RuntimeException("库存调整正忙，请稍后重试");
            }
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("库存调整正忙，请稍后重试");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // ==================== 幂等 / 审计 ====================

    private String normalizeKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return null;
        }
        String trimmed = idempotencyKey.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private StockSyncVO replay(String key) {
        AdminIdempotency record = adminIdempotencyMapper.selectById(key);
        if (record == null) {
            return null;
        }
        try {
            StockSyncVO vo = objectMapper.readValue(record.getResponseJson(), StockSyncVO.class);
            if (vo.getMode() == null) {
                // 同一个键被用到了另一类写操作（例如创建活动），拒绝回放
                throw new RuntimeException("幂等键已被其他请求使用");
            }
            vo.setReplayed(true);
            return vo;
        } catch (JsonProcessingException e) {
            log.error("幂等记录解析失败：key={}", key, e);
            throw new RuntimeException("幂等记录解析失败，请联系管理员");
        }
    }

    /**
     * 幂等记录写失败不影响已经生效的调整（Redis 与 DB 都已改），只记日志等人工核对。
     */
    private void saveIdempotency(String key, StockSyncVO vo) {
        try {
            AdminIdempotency record = new AdminIdempotency();
            record.setIdempotencyKey(key);
            record.setResponseJson(objectMapper.writeValueAsString(vo));
            adminIdempotencyMapper.insert(record);
        } catch (Exception e) {
            log.error("写库存调整幂等记录失败（本次调整已生效）：key={}", key, e);
        }
    }

    private String resolveToolName(String toolName) {
        if (toolName == null || toolName.trim().isEmpty()) {
            return "adjust_seckill_stock";
        }
        return toolName.trim();
    }

    private Map<String, Object> auditParams(Long eventId, Integer delta, String reason) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("eventId", eventId);
        params.put("delta", delta);
        params.put("reason", reason);
        return params;
    }

    private void compensate(Long productId, int delta) {
        try {
            seckillEventCache.incrementStock(productId, -(long) delta);
        } catch (RuntimeException e) {
            log.error("Redis 库存补偿失败，需人工核对：productId={}, delta={}", productId, delta, e);
        }
    }
}
