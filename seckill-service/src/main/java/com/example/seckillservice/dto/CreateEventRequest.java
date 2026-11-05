package com.example.seckillservice.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 创建秒杀活动入参，对应 contracts/tools.md §1.1。
 * 校验统一在 Service 里做，保证返回给 Agent 的是契约规定的中文 message。
 */
@Data
public class CreateEventRequest {

    private Long productId;

    private BigDecimal seckillPrice;

    private Integer stock;

    private LocalDateTime startTime;

    private LocalDateTime endTime;
}
