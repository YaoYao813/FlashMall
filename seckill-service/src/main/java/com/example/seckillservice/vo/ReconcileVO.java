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

    /**
     * DRIFT 的可能成因指纹，非 DRIFT 一律 {@code NONE}：
     * <pre>
     *   DUPLICATE_ROLLBACK        两侧 gap 都 &lt; 0：同一回滚消息执行了两次（多退）
     *   REDIS_OVER_ROLLBACK       仅配额侧 gap &lt; 0：Redis 多退了
     *   WAREHOUSE_OVER_ROLLBACK   仅仓库侧 gap &lt; 0：product_stock 多退了
     *   ROLLBACK_MISSED           至少一侧 gap &gt; 0：退单没归还库存，或库存扣了订单没落库（少退）
     *   MIXED_SIGN                两侧方向相反：不是一个原因，需人工判断
     *   NONE                      非 DRIFT
     * </pre>
     * 只是「可能成因」，不是定论——对账只读，不据此自动修复。
     */
    private String fingerprint;

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
