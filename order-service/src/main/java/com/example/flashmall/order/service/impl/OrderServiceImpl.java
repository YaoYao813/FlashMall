package com.example.flashmall.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.flashmall.common.entity.Order;
import com.example.flashmall.common.mapper.OrderMapper;
import com.example.flashmall.common.util.PageResult;
import com.example.flashmall.order.enums.OrderStatus;
import com.example.flashmall.order.service.OrderService;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    // 创建订单
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
        orderMapper.insert(order); // 插入数据库

        // 3. 发送延迟消息：15分钟后检查是否支付，未支付则超时取消
        sendDelayCancelMessage(order.getId(), 15);
        return order.getId();
    }

    // 取消订单
    @Override
    @Transactional
    public boolean cancelOrder(Long orderId) {
        Order order = orderMapper.selectById(orderId);// 依据Id从数据库找到对应订单
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }
        OrderStatus currentStatus = OrderStatus.values()[order.getStatus()];

        // 验证订单是否可以转为“已取消”状态
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

    // 订单列表分页查询 提供给用户端 "我的订单" 页面使用 分页查询指定用户的所有订单 按创建时间倒序排列
    @Override
    public PageResult<Order> listOrders(Long userId, Integer page, Integer size) {
        // 1. 创建分页对象：当前页、每页条数
        Page<Order> pageInfo = new Page<>(page, size);

        // 2. 构建Lambda查询条件
        LambdaQueryWrapper<Order> query = new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .orderByDesc(Order::getCreateTime);

        // 3. 执行分页查询
        Page<Order> result = orderMapper.selectPage(pageInfo, query);

        // 4. 封装统一的分页返回结果
        return new PageResult<>(result.getRecords(), result.getTotal(), result.getPages());
    }

    @Override
    public Order getOrderByOrderNo(String orderNo) {
        LambdaQueryWrapper<Order> query = new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo);
        return orderMapper.selectOne(query);
    }

    private String generateOrderNo() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return "ORD" + timestamp + uuid;
    }

    /**
     * 发送延迟取消消息（RabbitMQ 延迟消息插件，逐个判断延迟，不受队头阻塞影响）
     */
    private void sendDelayCancelMessage(Long orderId, int delayMinutes) {
        rabbitTemplate.convertAndSend(
                "order.delay.exchange",
                "order.cancel",
                orderId,
                message -> {
                    message.getMessageProperties().setDelay(delayMinutes * 60 * 1000);
                    return message;
                }
        );
    }

    /**
     * 回滚库存（通知 seckill-service）
     */
    private void rollbackStock(Long productId, Long userId) {
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
