package com.example.seckillservice.service.impl;

import com.example.flashmall.common.entity.SeckillMessage;
import com.example.flashmall.common.mapper.OrderMapper;
import com.example.flashmall.common.mapper.ProductStockMapper;
import com.example.seckillservice.cache.SeckillEventCache;
import com.example.seckillservice.config.RabbitMQConfig;
import com.example.seckillservice.dto.SeckillEventWindow;
import com.example.seckillservice.script.SeckillRedisScript;
import com.example.seckillservice.service.SeckillService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Slf4j
@Service
public class SeckillServiceImpl implements SeckillService {

    @Autowired
    private ProductStockMapper productStockMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private SeckillRedisScript seckillRedisScript;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private SeckillEventCache seckillEventCache;

    // 秒杀业务
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String seckill(Long userId, Long productId) {

        // ========== 第零阶段：活动窗口校验（一次 GET，必须在 Lua 之前） ==========
        assertEventOpen(productId);

        // ========== 第一阶段：Redis + Lua 预校验（内存操作，微秒级） ==========
        Long result = seckillRedisScript.executeSeckill(productId, userId);
        if (result == 1) {
            throw new RuntimeException("库存不足");
        }
        if (result == 2) {
            throw new RuntimeException("您已抢购过该商品，请勿重复下单");
        }

        // ============ 第二阶段：生成订单号 + 发送 MQ 消息 =============
        String orderNo = generateOrderNo();
        SeckillMessage message = new SeckillMessage(userId, productId, orderNo);
        try {
            // 组装消息发到 RabbitMQ 的交换机
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.SECKILL_EXCHANGE,
                    RabbitMQConfig.SECKILL_ORDER_ROUTING_KEY,
                    message // 消息
            );
            log.info("秒杀消息已发送：userId={}, productId={}, orderNo={}", userId, productId, orderNo);
        } catch (Exception e) {
            // MQ 发送失败，回滚 Redis 库存
            log.error("MQ发送失败，回滚Redis库存：userId={}, productId={}", userId, productId, e);
            seckillRedisScript.rollbackStock(productId, userId);
            throw new RuntimeException("系统繁忙，请稍后重试");
        }
        return orderNo;
    }

    /**
     * 活动窗口校验：只读 Redis，不查库、不加锁、不改 Lua。
     */
    private void assertEventOpen(Long productId) {
        SeckillEventWindow window = seckillEventCache.readWindow(productId);
        if (window == null) {
            throw new RuntimeException("活动不存在或未预热");
        }
        long now = System.currentTimeMillis();
        if (now < window.getStartTs()) {
            throw new RuntimeException("活动未开始");
        }
        if (now > window.getEndTs()) {
            throw new RuntimeException("活动已结束");
        }
    }

    // 创建订单号
    private String generateOrderNo() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))
                + (UUID.randomUUID().toString().hashCode() & Integer.MAX_VALUE);
    }

}
