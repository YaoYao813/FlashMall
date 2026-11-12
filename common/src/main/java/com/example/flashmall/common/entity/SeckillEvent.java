package com.example.flashmall.common.entity;

import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@Data
@TableName("seckill_event")
public class SeckillEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    // 主键
    @TableId(type = IdType.AUTO)
    private Long id;

    // 关联商品ID
    private Long productId;

    // 秒杀价格
    private BigDecimal seckillPrice;

    // 活动库存数（秒杀核心库存，存入Redis）
    private Integer stock;

    // 预热那一刻的仓库库存（D0）。对账要拿它判定仓库侧消耗，历史活动为 null
    private Integer warehouseBaseline;

    // 秒杀开始时间
    private LocalDateTime startTime;

    // 秒杀结束时间
    private LocalDateTime endTime;
}