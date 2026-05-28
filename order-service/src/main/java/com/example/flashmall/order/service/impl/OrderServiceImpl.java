package com.example.flashmall.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.flashmall.common.entity.Order;
import com.example.flashmall.common.mapper.OrderMapper;
import com.example.flashmall.order.enums.OrderStatus;
import com.example.flashmall.order.service.OrderService;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Override
    public Long createOrder(Long userId, Long productId, Integer quantity, Long addressId) {
        // 1. 生成订单号
        String orderNo = generateOrderNo();

        // 2. 创建订单（状态：待支付）
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setProductId(productId);
        order.setProductName("普通商品-" + productId);
        order.setPrice(new BigDecimal("99.99"));
        order.setQuantity(quantity);
        order.setTotalAmount(order.getPrice().multiply(BigDecimal.valueOf(quantity)));
        order.setStatus(OrderStatus.PENDING_PAY.getCode());
        order.setAddressId(addressId);
        orderMapper.insert(order);

        // 3. 发送延迟消息：15分钟后检查是否支付，未支付则超时取消
        sendDelayCancelMessage(order.getId(), 15);
        return order.getId();
    }

    @Override
    @Transactional
    public boolean cancelOrder(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }
        OrderStatus currentStatus = OrderStatus.values()[order.getStatus()];
        if (!currentStatus.canTransitionTo(OrderStatus.CANCELLED)) {
            throw new RuntimeException("当前状态不允许取消");
        }
        // 更新状态
        order.setStatus(OrderStatus.CANCELLED.getCode());
        orderMapper.updateById(order);
        // 回滚库存（发送消息到秒杀服务）
        rollbackStock(order.getProductId(), order.getUserId());
        return true;
    }

    @Override
    public PageResult<Order> listOrders(Long userId, Integer page, Integer size) {
        Page<Order> pageInfo = new Page<>(page, size);
        LambdaQueryWrapper<Order> query = new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .orderByDesc(Order::getCreateTime);
        Page<Order> result = orderMapper.selectPage(pageInfo, query);
        return new PageResult<>(result.getRecords(), result.getTotal(), result.getPages());
    }

    /**
     * 发送延迟取消消息（RabbitMQ 死信队列实现）
     */
    private void sendDelayCancelMessage(Long orderId, int delayMinutes) {
        rabbitTemplate.convertAndSend(
                "order.delay.exchange",
                "order.delay.cancel",
                orderId,
                message -> {
                    message.getMessageProperties().setDelay(delayMinutes * 60 * 1000);  // 毫秒
                    return message;
                }
        );
    }

    /**
     * 回滚库存（通知 seckill-service）
     */
    private void rollbackStock(Long productId, Long userId) {
        // 发送消息到秒杀服务的库存回滚队列
        Map<String, Object> msg = new HashMap<>();
        msg.put("productId", productId);
        msg.put("userId", userId);
        rabbitTemplate.convertAndSend(
                "seckill.direct.exchange",
                "seckill.stock.rollback",
                msg
        );
    }
}
