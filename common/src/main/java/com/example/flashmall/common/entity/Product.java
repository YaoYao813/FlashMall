package com.example.flashmall.common.entity;

import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@Data
@TableName("product")
public class Product implements Serializable {

    private static final long serialVersionUID = 1L;

    // 主键
    @TableId(type = IdType.AUTO)
    private Long id;

    // 店铺ID
    private Long shopId;

    // 商品标题
    private String title;

    // 原价
    private BigDecimal price;

    // 商品描述
    private String description;

    // 普通库存（秒杀时库存由Redis管理，此字段可设为0）
    private Integer stock;

    // 商品状态：1=上架，0=下架
    private Integer status;

    // 创建时间
    private LocalDateTime createTime;
}