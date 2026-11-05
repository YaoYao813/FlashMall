package com.example.seckillservice.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 活动统计快照，口径见 agent-service/contracts/tools.md §1.5。
 *
 * <p>库存未知时（Redis 不可达、仓库行缺失），对应字段为 null 而不是 0。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventStatsVO {

    private EventVO event;

    private Stats stats;

    private Stock stock;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Stats {

        private Integer orderCount;

        private Integer paidCount;

        /** paidCount × event.seckill_price，2 位小数 */
        private BigDecimal gmv;

        /** paidCount / orderCount，4 位小数；无订单时为 0 */
        private BigDecimal payRate;

        /** 活动总配额 event.stock */
        private Integer stockTotal;

        /** 净消耗 = stockTotal - redisStock；Redis 不可达时为 null */
        private Integer stockConsumed;

        /** stockConsumed / stockTotal；分母为 0 或 Redis 不可达时为 null */
        private BigDecimal consumeRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Stock {

        /** product_stock.stock（仓库剩余），与 stock-sync 的 dbStock(event.stock) 不同义 */
        private Integer dbStock;

        /** Redis 剩余可抢；Redis 不可达时为 null */
        private Integer redisStock;

        /** status=0 的订单数 */
        private Integer ordersOutstanding;
    }
}
