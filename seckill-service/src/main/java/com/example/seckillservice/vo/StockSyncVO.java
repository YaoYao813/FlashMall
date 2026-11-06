package com.example.seckillservice.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 库存同步结果。
 * dbStock 是活动总配额（seckill_event.stock），redisStock 是当前剩余可抢。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockSyncVO {

    private Integer dbStock;
    private Integer redisStock;
    private String mode;

    /** 命中同一 Idempotency-Key 的回放时为 true，表示库存未被再次调整 */
    private Boolean replayed;
}
