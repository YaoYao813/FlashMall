package com.example.flashmall.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.flashmall.common.entity.Order;
import com.example.flashmall.common.entity.StockRollbackMessage;
import com.example.flashmall.common.mapper.OrderMapper;
import com.example.flashmall.common.util.PageResult;
import com.example.flashmall.order.enums.OrderStatus;
import com.example.flashmall.order.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
@Slf4j
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

    /**
     * 取消订单。
     *
     * <p>状态变更是 <b>CAS</b>（{@code WHERE status = 0}），不是「先查再改」：
     * 取消接口和 15 分钟后的超时消费可能同时到达，两边都读到「待支付」，
     * 后写的那个会把对方的状态覆盖掉——用户刚付完钱，订单却变成已取消。
     *
     * <p>这里不再用 {@code @Transactional}：CAS 是单条语句，本来就没有多表事务；
     * 而「改状态」和「发回滚消息」之间不该有数据库事务包住——消息发不出去时
     * 事务回滚会把状态改回去，用户就会陷入「点了取消但还是待支付」的死循环。
     * 现在的语义是：状态一旦改成功就保留，回滚消息由重试补齐（消费端按 orderId 幂等）。
     */
    @Override
    public boolean cancelOrder(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }
        if (order.getStatus() == OrderStatus.CANCELLED.getCode()) {
            // 已经是取消态：上一次可能死在「状态改成功、回滚消息没发出去」之间。
            // 再发一次（消费端按 orderId 幂等），并且告诉用户「取消成功」而不是报错。
            rollbackStock(order, "CANCEL");
            return true;
        }
        OrderStatus currentStatus = OrderStatus.values()[order.getStatus()];
        if (!currentStatus.canTransitionTo(OrderStatus.CANCELLED)) {
            throw new RuntimeException("当前状态不允许取消");
        }

        int affected = orderMapper.casTerminate(orderId, OrderStatus.CANCELLED.getCode());
        if (affected > 0) {
            rollbackStock(order, "CANCEL");
            return true;
        }

        // CAS 失败：可能是并发请求已经把它改成已取消了（例如用户连点两次）。
        // 此时必须把回滚消息再发一次——上一次可能就死在「状态已改、消息未发」的窗口里。
        // 重复发送是安全的：消费端靠 stock_rollback_log.order_id 去重。
        Order latest = orderMapper.selectById(orderId);
        if (latest != null && latest.getStatus() == OrderStatus.CANCELLED.getCode()) {
            rollbackStock(latest, "CANCEL");
            return true;
        }
        throw new RuntimeException("当前状态不允许取消");
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
     * 回滚库存（通知 seckill-service）。
     *
     * <p>消息里必须带 orderId：消费端用它做幂等键，重复投递时靠它判断「这单已经退过」。
     *
     * <p>发送失败只记日志不抛异常：此时订单状态已经是终态，抛出去只会让用户以为
     * 取消失败、反复重试。库存少退会被对账（配额侧 gap &gt; 0）发现，属于可修的偏差；
     * 而状态回滚或重复回滚才是不可修的。宁可少卖。
     */
    private void rollbackStock(Order order, String reason) {
        try {
            rabbitTemplate.convertAndSend(
                    "seckill.direct.exchange",
                    "seckill.stock.rollback",
                    StockRollbackMessage.of(order, reason)
            );
        } catch (Exception e) {
            log.error("库存回滚消息发送失败，需人工对账：orderId={}, orderNo={}, productId={}",
                    order.getId(), order.getOrderNo(), order.getProductId(), e);
        }
    }
}
