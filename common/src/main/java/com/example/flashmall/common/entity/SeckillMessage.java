package com.example.flashmall.common.entity;

import java.io.Serializable;

public class SeckillMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long userId;
    private Long productId;
    private String orderNo;       // 预生成的订单号

    public SeckillMessage() {
    }
    public SeckillMessage(Long userId, Long productId, String orderNo) {
        this.userId = userId;
        this.productId = productId;
        this.orderNo = orderNo;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }
}
