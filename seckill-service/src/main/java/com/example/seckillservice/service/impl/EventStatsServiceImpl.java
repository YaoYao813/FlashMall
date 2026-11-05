package com.example.seckillservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.flashmall.common.entity.Order;
import com.example.flashmall.common.entity.ProductStock;
import com.example.flashmall.common.entity.SeckillEvent;
import com.example.flashmall.common.mapper.OrderMapper;
import com.example.flashmall.common.mapper.ProductStockMapper;
import com.example.flashmall.common.mapper.SeckillEventMapper;
import com.example.seckillservice.cache.SeckillEventCache;
import com.example.seckillservice.enums.EventStatus;
import com.example.seckillservice.service.EventStatsService;
import com.example.seckillservice.vo.EventStatsVO;
import com.example.seckillservice.vo.EventVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 活动统计快照。只读，不加锁，不改任何业务数据。
 *
 * <p>口径见 agent-service/contracts/tools.md §1.5。两个容易踩的点：
 * <ul>
 *   <li>GMV 用 paidCount × event.seckill_price，不能 SUM(order.total_amount)：
 *       消费者落库写的是 product.price（原价）；</li>
 *   <li>stock.dbStock 取 product_stock.stock（仓库剩余），不是 event.stock（活动总配额）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventStatsServiceImpl implements EventStatsService {

    /** OrderStatus：0=待支付, 1=已支付, 2=已发货, 3=已完成, 4=已取消, 5=超时 */
    private static final List<Integer> PAID_STATUSES =
            Collections.unmodifiableList(Arrays.asList(1, 2, 3));
    private static final List<Integer> PENDING_PAY_STATUS =
            Collections.singletonList(0);

    private static final int RATE_SCALE = 4;
    private static final int GMV_SCALE = 2;

    private final SeckillEventMapper seckillEventMapper;
    private final OrderMapper orderMapper;
    private final ProductStockMapper productStockMapper;
    private final SeckillEventCache seckillEventCache;

    @Override
    public EventStatsVO stats(Long eventId) {
        if (eventId == null) {
            throw new RuntimeException("活动ID不能为空");
        }
        SeckillEvent event = seckillEventMapper.selectById(eventId);
        if (event == null) {
            throw new RuntimeException("活动不存在");
        }

        int orderCount = countOrders(event, null);
        int paidCount = countOrders(event, PAID_STATUSES);
        int ordersOutstanding = countOrders(event, PENDING_PAY_STATUS);

        BigDecimal gmv = event.getSeckillPrice() == null
                ? BigDecimal.ZERO.setScale(GMV_SCALE)
                : event.getSeckillPrice()
                        .multiply(BigDecimal.valueOf(paidCount))
                        .setScale(GMV_SCALE, RoundingMode.HALF_UP);

        Integer redisStock = readRedisStock(event.getProductId());
        Integer stockTotal = event.getStock();
        Integer stockConsumed = redisStock == null || stockTotal == null
                ? null : stockTotal - redisStock;
        BigDecimal consumeRate = stockConsumed == null || stockTotal == null || stockTotal == 0
                ? null : ratio(stockConsumed, stockTotal);

        return EventStatsVO.builder()
                .event(toVO(event))
                .stats(EventStatsVO.Stats.builder()
                        .orderCount(orderCount)
                        .paidCount(paidCount)
                        .gmv(gmv)
                        .payRate(ratio(paidCount, orderCount))
                        .stockTotal(stockTotal)
                        .stockConsumed(stockConsumed)
                        .consumeRate(consumeRate)
                        .build())
                .stock(EventStatsVO.Stock.builder()
                        .dbStock(readWarehouseStock(event.getProductId()))
                        .redisStock(redisStock)
                        .ordersOutstanding(ordersOutstanding)
                        .build())
                .build();
    }

    /**
     * 订单统计窗口：product_id + create_time 落在活动区间。
     * 同一商品不允许两场未结束活动，因此不会串到其他场次。
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

    /**
     * Redis 不可达时返回 null，由上层把相关指标一并置空，不用 0 冒充。
     */
    private Integer readRedisStock(Long productId) {
        try {
            return seckillEventCache.getStock(productId);
        } catch (DataAccessException e) {
            log.warn("读取 Redis 秒杀库存失败，统计按未知处理：productId={}", productId, e);
            return null;
        }
    }

    private Integer readWarehouseStock(Long productId) {
        ProductStock warehouse = productStockMapper.selectByProductId(productId);
        return warehouse == null ? null : warehouse.getStock();
    }

    private BigDecimal ratio(int numerator, int denominator) {
        if (denominator == 0) {
            return BigDecimal.ZERO.setScale(RATE_SCALE);
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), RATE_SCALE, RoundingMode.HALF_UP);
    }

    private EventVO toVO(SeckillEvent event) {
        return EventVO.builder()
                .id(event.getId())
                .productId(event.getProductId())
                .seckillPrice(event.getSeckillPrice())
                .stock(event.getStock())
                .startTime(event.getStartTime())
                .endTime(event.getEndTime())
                .status(EventStatus.of(event.getStartTime(), event.getEndTime(),
                        LocalDateTime.now()).getCode())
                .build();
    }
}
