package com.example.seckillservice.service.impl;

import com.example.flashmall.common.entity.SeckillMessage;
import com.example.flashmall.common.mapper.OrderMapper;
import com.example.flashmall.common.mapper.ProductStockMapper;
import com.example.seckillservice.config.RabbitMQConfig;
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

    // 秒杀业务
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long seckill(Long userId, Long productId) {

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
        // 返回 0 表示"排队中"（异步处理），Controller 层会解析
        return 0L;
    }

    // 创建订单号
    private String generateOrderNo() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))
                + (UUID.randomUUID().toString().hashCode() & Integer.MAX_VALUE);
    }

}
