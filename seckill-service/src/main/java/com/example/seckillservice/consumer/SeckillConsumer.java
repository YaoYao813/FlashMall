package com.example.seckillservice.consumer;

import com.example.flashmall.common.entity.SeckillMessage;
import com.example.seckillservice.config.RabbitMQConfig;
import com.example.seckillservice.script.SeckillRedisScript;
import com.example.seckillservice.service.SeckillOrderBatchService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 秒杀订单消费者。
 *
 * <p>三条可靠性约定：
 * <ol>
 *   <li>落库逻辑在 {@link SeckillOrderBatchService} 里，单独 Bean 才能让 @Transactional 生效；</li>
 *   <li>某个商品分组失败时，只回补**这一组**的 Redis，不碰已经提交成功的其他商品；</li>
 *   <li>失败的消息 {@code basicNack(requeue=false)} 进死信队列 {@code seckill.order.dlq}，
 *       不再直接丢弃；重放时由 order_no 幂等过滤兜底。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillConsumer {

    private static final String LOCK_PREFIX = "lock:seckill:product:";
    private static final long LOCK_WAIT_SECONDS = 5;
    private static final long LOCK_LEASE_SECONDS = 30;

    private final SeckillOrderBatchService seckillOrderBatchService;
    private final SeckillRedisScript seckillRedisScript;
    private final RedissonClient redissonClient;

    @RabbitListener(queues = RabbitMQConfig.SECKILL_ORDER_QUEUE)
    public void handleSeckillOrder(List<SeckillMessage> messages, Channel channel,
                                   @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        if (messages == null || messages.isEmpty()) {
            channel.basicAck(deliveryTag, true);
            return;
        }
        log.info("收到批量秒杀消息，数量={}", messages.size());

        Map<Long, List<SeckillMessage>> grouped = messages.stream()
                .collect(Collectors.groupingBy(SeckillMessage::getProductId));

        List<Long> failedProductIds = new ArrayList<>();

        for (Map.Entry<Long, List<SeckillMessage>> entry : grouped.entrySet()) {
            Long productId = entry.getKey();
            List<SeckillMessage> batch = entry.getValue();
            RLock lock = redissonClient.getLock(LOCK_PREFIX + productId);
            boolean locked = false;
            try {
                locked = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
                if (!locked) {
                    // 锁竞争失败：把整条投递重新入队。重投的消息由 order_no 幂等过滤挡住，
                    // 已成功落库的部分不会被重复扣减。
                    log.warn("批量处理获取分布式锁超时，重新入队：productId={}", productId);
                    channel.basicNack(deliveryTag, true, true);
                    return;
                }
                seckillOrderBatchService.processBatchOrders(productId, batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.basicNack(deliveryTag, true, true);
                return;
            } catch (Exception e) {
                // 只回补这一组：事务已回滚，这些消息对应的订单没有落库
                log.error("商品批次处理失败：productId={}, count={}", productId, batch.size(), e);
                failedProductIds.add(productId);
                rollbackGroup(batch);
            } finally {
                if (locked && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }

        if (!failedProductIds.isEmpty()) {
            // 一次投递只有一个 deliveryTag，无法部分 ack；
            // 整条投递进死信队列，已成功的分组重放时会被幂等过滤跳过。
            log.error("秒杀订单批次部分失败，转入死信队列：failedProductIds={}, total={}",
                    failedProductIds, messages.size());
            channel.basicNack(deliveryTag, true, false);
            return;
        }

        channel.basicAck(deliveryTag, true);
        log.info("批量秒杀订单处理成功，数量={}", messages.size());
    }

    /**
     * 回补 Redis。DB 事务已回滚，所以这一组的订单一定没有落库。
     */
    private void rollbackGroup(List<SeckillMessage> batch) {
        for (SeckillMessage msg : batch) {
            try {
                seckillRedisScript.rollbackStock(msg.getProductId(), msg.getUserId());
            } catch (Exception ex) {
                log.error("回滚Redis失败：userId={}, productId={}",
                        msg.getUserId(), msg.getProductId(), ex);
            }
        }
    }
}
