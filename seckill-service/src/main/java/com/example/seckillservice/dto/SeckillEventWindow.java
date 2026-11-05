package com.example.seckillservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 秒杀活动窗口，预热时写入 Redis seckill:event:{productId}。
 * startTs / endTs 统一用 epoch 毫秒，避免时区与格式化差异。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillEventWindow {

    private Long eventId;
    private Long startTs;
    private Long endTs;
}
