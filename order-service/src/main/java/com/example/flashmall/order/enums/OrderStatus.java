package com.example.flashmall.order.enums;

// 订单状态 (枚举)
public enum OrderStatus {
    PENDING_PAY(0, "待支付"),
    PAID(1, "已支付"),
    SHIPPED(2, "已发货"),
    COMPLETED(3, "已完成"),
    CANCELLED(4, "已取消"),
    TIMEOUT(5, "已超时");

    private final int code;
    private final String desc;

    OrderStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() { return code; }
    public String getDesc() { return desc; }

    /**
     * 状态流转校验：判断当前状态是否允许变更到目标状态
     */
    public boolean canTransitionTo(OrderStatus target) {
        switch (this) {
            case PENDING_PAY:// 待支付
                return target == PAID || target == CANCELLED || target == TIMEOUT; // 待支付 --> 已支付/已取消/已超时
            case PAID:// 已支付
                return target == SHIPPED; // 已支付 --> 已发货
            case SHIPPED:// 已发货
                return target == COMPLETED; // 已发货 --> 已完成
            default:
                return false;  // 已完成/已取消/已超时不能再变更
        }
    }
}
