package com.example.flashmall.common.entity;

import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@Data
@TableName("`order`")
public class Order implements Serializable {

    private static final long serialVersionUID = 1L;

    // 主键
    @TableId(type = IdType.AUTO)
    private Long id;

    // 订单编号（唯一业务标识）
    private String orderSn;

    // 用户ID
    private Long userId;

    // 关联商品ID（冗余字段，方便查询）
    private Long productId;

    // 秒杀活动ID（非秒杀订单可为NULL）
    private Long seckillEventId;

    // 实际支付金额
    private BigDecimal amount;

    // 订单状态：0=待支付，1=已支付，2=已取消
    private Integer status;

    // 订单创建时间
    private LocalDateTime createTime;

    // 支付时间
    private LocalDateTime payTime;
}