package com.example.seckillservice.consumer;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.flashmall.common.entity.ProductStock;
import com.example.flashmall.common.mapper.ProductStockMapper;
import com.example.seckillservice.script.SeckillRedisScript;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.Channel;
import java.util.Map;

// 回滚消费者
@Component
@Slf4j
public class StockRollbackConsumer {
    @Autowired
    private ProductStockMapper productStockMapper;

    @Autowired
    private SeckillRedisScript seckillRedisScript;

    @RabbitListener(queues = "seckill.stock.rollback.queue")
    public void handleStockRollback(Map<String, Object> msg, Channel channel,
                                    @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            Long productId = Long.valueOf(msg.get("productId").toString());
            Long userId = Long.valueOf(msg.get("userId").toString());
            // 回滚 MySQL 库存
            productStockMapper.update(null,
                    new LambdaUpdateWrapper<ProductStock>()
                            .eq(ProductStock::getProductId, productId)
                            .setSql("stock = stock + 1")
            );
            // 回滚 Redis 库存 + 删除用户标记
            seckillRedisScript.rollbackStock(productId, userId);
            channel.basicAck(deliveryTag, false);
            log.info("库存回滚成功：productId={}, userId={}", productId, userId);
        } catch (Exception e) {
            try {
                channel.basicNack(deliveryTag, false, true);
            } catch (IOException ex) {
                log.error("ACK失败", ex);
            }
        }
    }
}
