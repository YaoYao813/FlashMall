package com.example.seckillservice.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 修改/下线活动入参，对应 contracts/tools.md §1.3。
 * 字段均可选，至少传一个；进行中的活动只允许改 endTime。
 */
@Data
public class UpdateEventRequest {

    private BigDecimal seckillPrice;

    private Integer stock;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    public boolean isEmpty() {
        return seckillPrice == null && stock == null && startTime == null && endTime == null;
    }
}
