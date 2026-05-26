package com.example.seckillservice.consumer;

// 消费者（异步消费 MQ 消息，写数据库）
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.flashmall.common.entity.Order;
import com.example.flashmall.common.entity.ProductStock;
import com.example.flashmall.common.entity.SeckillMessage;
import com.example.flashmall.common.mapper.OrderMapper;
import com.example.flashmall.common.mapper.ProductStockMapper;
import com.example.seckillservice.config.RabbitMQConfig;
import com.example.seckillservice.script.SeckillRedisScript;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class SeckillConsumer {
    @Autowired
    private ProductStockMapper productStockMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private SeckillRedisScript seckillRedisScript;
    @Autowired
    private RedissonClient redissonClient;
    private static final int MAX_RETRY = 3;  // 最大重试次数
    /**
     * 监听秒杀订单队列，异步处理下单
     */
    @RabbitListener(queues = RabbitMQConfig.SECKILL_ORDER_QUEUE)
    public void handleSeckillOrder(SeckillMessage message, Channel channel,
                                   @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        Long userId = message.getUserId();
        Long productId = message.getProductId();
        String orderNo = message.getOrderNo();
        log.info("收到秒杀消息：userId={}, productId={}, orderNo={}", userId, productId, orderNo);
        // ========== 分布式锁：保证同一商品的库存扣减串行 ==========
        String lockKey = "lock:seckill:product:" + productId;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            // 尝试获取锁：最多等待 5 秒，锁持有时间 30 秒
            if (lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                try {
                    // 执行数据库操作
                    processOrder(userId, productId, orderNo);
                    // 成功 → 手动确认消息
                    channel.basicAck(deliveryTag, false);
                    log.info("秒杀订单处理成功：orderNo={}", orderNo);
                } finally {
                    lock.unlock();
                }
            } else {
                // 获取锁超时 → 消息重新排队
                log.warn("获取分布式锁超时：productId={}", productId);
                channel.basicNack(deliveryTag, false, true);  // requeue=true，重新入队
            }
        } catch (Exception e) {
            log.error("处理秒杀订单异常：orderNo={}", orderNo, e);
            try {
                // 失败 → 回滚 Redis + 拒绝消息
                seckillRedisScript.rollbackStock(productId, userId);
                // requeue=false → 不重新入队，避免死循环
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException ex) {
                log.error("消息确认失败", ex);
            }
        }
    }
    /**
     * 数据库落单逻辑
     */
    @Transactional(rollbackFor = Exception.class)
    public void processOrder(Long userId, Long productId, String orderNo) {
        // 1. 查询 MySQL 库存
        LambdaQueryWrapper<ProductStock> query = new LambdaQueryWrapper<ProductStock>()
                .eq(ProductStock::getProductId, productId);
        ProductStock stock = productStockMapper.selectOne(query);
        if (stock == null || stock.getStock() <= 0) {
            throw new RuntimeException("MySQL库存不足");
        }
        // 2. 乐观锁扣减 MySQL 库存
        LambdaUpdateWrapper<ProductStock> update = new LambdaUpdateWrapper<>();
        update.eq(ProductStock::getProductId, productId)
                .eq(ProductStock::getVersion, stock.getVersion())
                .setSql("stock = stock - 1, version = version + 1");
        int affected = productStockMapper.update(null, update);
        if (affected == 0) {
            throw new RuntimeException("乐观锁冲突");
        }
        // 3. 创建订单
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setProductId(productId);
        order.setProductName("秒杀商品-" + productId);
        order.setPrice(new BigDecimal("0.01"));
        order.setQuantity(1);
        order.setTotalAmount(order.getPrice());
        order.setStatus(0);
        orderMapper.insert(order);
    }
}