package com.example.seckillservice.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.flashmall.common.entity.Order;
import com.example.flashmall.common.entity.Product;
import com.example.flashmall.common.entity.ProductStock;
import com.example.flashmall.common.entity.SeckillMessage;
import com.example.flashmall.common.mapper.OrderMapper;
import com.example.flashmall.common.mapper.ProductMapper;
import com.example.flashmall.common.mapper.ProductStockMapper;
import com.example.seckillservice.config.RabbitMQConfig;
import com.example.seckillservice.script.SeckillRedisScript;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class SeckillConsumer {
    @Autowired
    private ProductStockMapper productStockMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private SeckillRedisScript seckillRedisScript;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 批量监听秒杀订单队列，按 productId 分组后批量落库
     */
    @RabbitListener(queues = RabbitMQConfig.SECKILL_ORDER_QUEUE)
    public void handleSeckillOrder(List<SeckillMessage> messages, Channel channel,
                                   @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        log.info("收到批量秒杀消息，数量={}", messages.size());

        // 按 productId 分组
        Map<Long, List<SeckillMessage>> grouped = messages.stream()
                .collect(Collectors.groupingBy(SeckillMessage::getProductId));

        try {
            for (Map.Entry<Long, List<SeckillMessage>> entry : grouped.entrySet()) {
                Long productId = entry.getKey();
                List<SeckillMessage> batch = entry.getValue();

                String lockKey = "lock:seckill:product:" + productId;
                RLock lock = redissonClient.getLock(lockKey);
                if (lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                    try {
                        processBatchOrders(productId, batch);
                    } finally {
                        lock.unlock();
                    }
                } else {
                    log.warn("批量处理获取分布式锁超时：productId={}", productId);
                    // 锁获取失败，整批重新入队
                    channel.basicNack(deliveryTag, true, true);
                    return;
                }
            }
            // 全部成功 → 批量确认
            channel.basicAck(deliveryTag, true);
            log.info("批量秒杀订单处理成功，数量={}", messages.size());
        } catch (Exception e) {
            log.error("批量处理秒杀订单异常，数量={}", messages.size(), e);
            // 回滚所有消息的 Redis 状态
            for (SeckillMessage msg : messages) {
                try {
                    seckillRedisScript.rollbackStock(msg.getProductId(), msg.getUserId());
                } catch (Exception ex) {
                    log.error("回滚Redis失败：userId={}, productId={}", msg.getUserId(), msg.getProductId(), ex);
                }
            }
            try {
                channel.basicNack(deliveryTag, true, false);
            } catch (IOException ex) {
                log.error("消息确认失败", ex);
            }
        }
    }

    /**
     * 同一商品的批量落库：一次查库存 → 一次扣库存 → 批量插订单
     */
    @Transactional(rollbackFor = Exception.class)
    public void processBatchOrders(Long productId, List<SeckillMessage> batch) {
        int count = batch.size();

        // 1. 查询 MySQL 库存
        LambdaQueryWrapper<ProductStock> query = new LambdaQueryWrapper<ProductStock>()
                .eq(ProductStock::getProductId, productId);
        ProductStock stock = productStockMapper.selectOne(query);
        if (stock == null || stock.getStock() < count) {
            throw new RuntimeException("MySQL库存不足，productId=" + productId + ", need=" + count);
        }

        // 2. 乐观锁批量扣减库存（一次 UPDATE）
        int affected = productStockMapper.decrementStock(productId, count, stock.getVersion());
        if (affected == 0) {
            throw new RuntimeException("乐观锁冲突，productId=" + productId);
        }

        // 3. 查询商品真实信息
        Product product = productMapper.selectById(productId);
        String productName = product != null ? product.getTitle() : "秒杀商品-" + productId;
        BigDecimal price = product != null ? product.getPrice() : BigDecimal.ZERO;

        // 4. 批量创建订单（一条 INSERT SQL）
        List<Order> orders = new ArrayList<>(count);
        for (SeckillMessage msg : batch) {
            Order order = new Order();
            order.setOrderNo(msg.getOrderNo());
            order.setUserId(msg.getUserId());
            order.setProductId(msg.getProductId());
            order.setProductName(productName);
            order.setPrice(price);
            order.setQuantity(1);
            order.setTotalAmount(price);
            order.setStatus(0);
            orders.add(order);
        }
        orderMapper.insertBatch(orders);

        // 5. 发送延迟取消消息（15分钟后未支付自动取消，回滚库存）
        for (Order order : orders) {
            rabbitTemplate.convertAndSend(
                    "order.delay.exchange",
                    "order.cancel",
                    order.getId(),
                    message -> {
                        message.getMessageProperties().setDelay(15 * 60 * 1000);
                        return message;
                    }
            );
        }
        log.info("已发送延迟取消消息，数量={}, orderNos={}",
                orders.size(), orders.stream().map(Order::getOrderNo).collect(Collectors.toList()));
    }
}
