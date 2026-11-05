package com.example.flashmall.common.entity;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
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
    @NotBlank(message = "商品标题不能为空")
    private String title;

    // 原价
    @NotNull(message = "商品价格不能为空")
    private BigDecimal price;

    // 商品描述
    private String description;

    // 商品状态：1=上架，0=下架
    // 库存不在这里：唯一真相源是 product_stock.stock（消费者实际扣减的表）
    private Integer status;

    // 创建时间
    private LocalDateTime createTime;
}