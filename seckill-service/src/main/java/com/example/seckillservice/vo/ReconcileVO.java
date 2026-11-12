package com.example.seckillservice.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 秒杀库存对账结果，口径见 agent-service/contracts/tools.md §1.7。
 *
 * <p>两条不变量，两条都含「净消耗」{@code O - X}：
 * <pre>
 *   配额侧：Q - R = O - X + 在途
 *   仓库侧：D0 - D = O - X + 在途
 * </pre>
 * 符号：Q=活动总配额，R=Redis 库存，D0=预热时仓库库存，D=当前仓库库存，
 * O=活动窗口内订单数，X=其中已取消/已超时数。
 *
 * <p>未知一律为 null，不用 0 冒充——0 会被读成「确定没库存」。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReconcileVO {

    private Long eventId;

    private Long productId;

    /** running / ended，按活动起止时间推导 */
    private String status;

    /** OK / PENDING / DRIFT / UNKNOWN / STALE */
    private String verdict;

    /** 在途容差 = 主队列 + 回滚队列当前 ready 消息数 */
    private Integer backlog;

    private Quota quota;

    private Warehouse warehouse;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Quota {

        /** Q：活动总配额 seckill_event.stock */
        private Integer total;

        /** R：Redis 剩余库存；Redis 不可达或未预热时为 null */
        private Integer redisStock;

        /** O：活动窗口内订单数 */
        private Integer orderCount;

        /** X：上述订单中已取消 / 已超时的数量 */
        private Integer cancelledCount;

        /** Q - (O - X)，正常应等于 R */
        private Integer expectedRedis;

        /** (Q - R) - (O - X)：正数表示「扣了 Redis 但订单还没落库」 */
        private Integer gap;

        /** OK / PENDING / DRIFT / UNKNOWN */
        private String verdict;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Warehouse {

        /** D0：预热时的仓库库存；历史活动为 null */
        private Integer baseline;

        /** D：当前 product_stock.stock */
        private Integer dbStock;

        /** D0 - (O - X)，正常应等于 dbStock */
        private Integer expectedDb;

        /** (D0 - D) - (O - X)：正数表示「仓库扣了但订单还没落库」 */
        private Integer gap;

        /** OK / PENDING / DRIFT / UNKNOWN */
        private String verdict;
    }
}
