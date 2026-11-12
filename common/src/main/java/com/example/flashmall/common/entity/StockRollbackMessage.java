package com.example.flashmall.common.entity;

import lombok.Data;

import java.io.Serializable;

/**
 * 库存回滚消息体。
 *
 * <p>{@code orderId} 是幂等键，必不可少：同一条回滚消息可能被重复投递，
 * 消费端靠它区分「第一次」和「重投」。老格式（只有 productId + userId）无法去重，
 * 消费端会拒绝处理并打 ERROR 日志——宁可少退，不可多退。
 *
 * <p>{@code orderNo} 不参与判定，只为人工排查时能和订单号对上。
 * {@code reason} 区分回滚来源（CANCEL 用户取消 / TIMEOUT 超时未付），台账里能看出是谁退的。
 */
@Data
public class StockRollbackMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单ID，幂等键 */
    private Long orderId;

    private String orderNo;

    private Long productId;

    private Long userId;

    /** CANCEL / TIMEOUT */
    private String reason;

    public static StockRollbackMessage of(Order order, String reason) {
        StockRollbackMessage msg = new StockRollbackMessage();
        msg.setOrderId(order.getId());
        msg.setOrderNo(order.getOrderNo());
        msg.setProductId(order.getProductId());
        msg.setUserId(order.getUserId());
        msg.setReason(reason);
        return msg;
    }
}
