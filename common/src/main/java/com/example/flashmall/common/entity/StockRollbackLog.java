package com.example.flashmall.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 库存回滚幂等台账。
 *
 * <p>主键就是 {@code order_id}：回滚的粒度是订单，重复投递时 INSERT 撞主键，
 * 整个事务放弃，库存不会被退第二次。
 *
 * <p>{@code create_time} 交给数据库默认值，实体不设值（MyBatis-Plus 默认
 * 只写非 null 字段），避免显式 NULL 覆盖 DEFAULT。
 */
@Data
@TableName("stock_rollback_log")
public class StockRollbackLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单ID，同时是幂等主键 */
    @TableId(type = IdType.INPUT)
    private Long orderId;

    private String orderNo;

    private Long productId;

    private Long userId;

    /** CANCEL / TIMEOUT */
    private String reason;

    private LocalDateTime createTime;
}
