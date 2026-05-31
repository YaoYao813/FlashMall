package com.example.flashmall.order.service;

import com.example.flashmall.common.entity.Order;
import com.example.flashmall.common.util.PageResult;

public interface OrderService {
    Long createOrder(Long userId, Long productId, Integer quantity, Long addressId);
    boolean cancelOrder(Long orderId);
    PageResult<Order> listOrders(Long userId, Integer page, Integer size);
    Order getOrderByOrderNo(String orderNo);
}
