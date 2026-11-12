package com.example.seckillservice.service;

import com.example.seckillservice.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 死信重放：把 {@code seckill.order.dlq} 里的消息原样投回主交换机。
 *
 * <p>为什么重放是安全的：消费端在扣库存之前先按 order_no 查已有订单，
 * 重放的消息如果上次已经落库，会被直接跳过，不会重复扣减。
 *
 * <p>用 basicGet（{@code receive}）而不是常驻消费者：死信要人看着处理，
 * 不希望它被自动吞掉。单条失败时把消息投回死信交换机，不丢。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DlqReplayService {

    private static final int MAX_REPLAY = 500;

    private final RabbitTemplate rabbitTemplate;

    public Map<String, Object> replay(int limit) {
        int max = limit <= 0 ? 50 : Math.min(limit, MAX_REPLAY);
        int replayed = 0;
        int failed = 0;

        Message message;
        while (replayed + failed < max
                && (message = rabbitTemplate.receive(RabbitMQConfig.SECKILL_ORDER_DLQ)) != null) {
            try {
                rabbitTemplate.send(RabbitMQConfig.SECKILL_EXCHANGE,
                        RabbitMQConfig.SECKILL_ORDER_ROUTING_KEY, message);
                replayed++;
            } catch (RuntimeException e) {
                failed++;
                log.error("死信重放失败，投回死信队列：messageId={}",
                        message.getMessageProperties().getMessageId(), e);
                try {
                    rabbitTemplate.send(RabbitMQConfig.SECKILL_DLX,
                            RabbitMQConfig.SECKILL_ORDER_DEAD_ROUTING_KEY, message);
                } catch (RuntimeException ex) {
                    log.error("死信回投也失败，该消息已丢失：messageId={}",
                            message.getMessageProperties().getMessageId(), ex);
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("replayed", replayed);
        result.put("failed", failed);
        result.put("limit", max);
        log.info("死信重放完成：replayed={}, failed={}, limit={}", replayed, failed, max);
        return result;
    }

    public long depth() {
        Long count = rabbitTemplate.execute(channel -> channel.messageCount(
                RabbitMQConfig.SECKILL_ORDER_DLQ));
        return count == null ? 0 : count;
    }
}
