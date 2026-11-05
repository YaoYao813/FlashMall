package com.example.seckillservice.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀活动出参，创建接口与列表接口共用。
 * productName 只有列表接口会填，创建接口通过 NON_NULL 省掉。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventVO {

    private Long id;

    private Long productId;

    private String productName;

    private BigDecimal seckillPrice;

    private Integer stock;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** pending / running / ended */
    private String status;

    /** 是否命中幂等回放；首次创建为 false */
    private Boolean replayed;
}
