package com.example.seckillservice.consumer;

import com.example.flashmall.common.entity.StockRollbackMessage;
import com.example.seckillservice.config.RabbitMQConfig;
import com.example.seckillservice.script.SeckillRedisScript;
import com.example.seckillservice.service.StockRollbackService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * 库存回滚消费者。
 *
 * <p>执行顺序固定为 <b>先 DB、后 Redis</b>：
 * <ul>
 *   <li>DB 先成、Redis 失败 → Redis 比真实库存少 1，结果是「少卖」，活动结束后库存有剩；
 *       对账（{@code GET /api/admin/seckill/reconcile}）会出现 quota 侧负向 gap，可定位可修。</li>
 *   <li>Redis 先成、DB 失败 → Redis 有货但仓库没有，是「多卖」，用户下单后库存扣不动，
 *       消息进死信，用户被卡住。宁可少卖。</li>
 * </ul>
 *
 * <p>幂等边界：DB 侧靠 {@code stock_rollback_log} 主键。Redis 侧<b>没有</b>幂等保护
 * （回滚脚本是无条件 INCR + 删用户标记），所以一旦台账判定「已回滚」，就绝不能再碰 Redis。
 */
@Component
@Slf4j
public class StockRollbackConsumer {

    private static final String REASON_CANCEL = "CANCEL";

    @Autowired
    private StockRollbackService stockRollbackService;

    @Autowired
    private SeckillRedisScript seckillRedisScript;

    @RabbitListener(queues = RabbitMQConfig.SECKILL_STOCK_ROLLBACK_QUEUE)
    public void handleStockRollback(Map<String, Object> msg, Channel channel,
                                    @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        StockRollbackMessage rollback = parse(msg);
        if (rollback == null) {
            // 老格式消息里没有 orderId，无法去重。此处 ack 掉并留 ERROR：
            // 宁可这单库存少退（后面靠对账发现），也不能赌它不是重复投递而多退。
            log.error("回滚消息缺少 orderId/orderNo，无法幂等处理，已丢弃：{}", msg);
            ack(channel, deliveryTag);
            return;
        }

        boolean applied;
        try {
            applied = stockRollbackService.rollbackOnce(rollback);
        } catch (Exception e) {
            // DB 不可用等瞬时故障：重新入队，等下次重试。台账没写成功，重试是安全的。
            log.error("库存回滚写库失败，消息重新入队：orderId={}", rollback.getOrderId(), e);
            nack(channel, deliveryTag, true);
            return;
        }

        if (!applied) {
            log.warn("重复回滚消息，已忽略（DB 与 Redis 均未再次变更）：orderId={}, productId={}",
                    rollback.getOrderId(), rollback.getProductId());
            ack(channel, deliveryTag);
            return;
        }

        try {
            // 回滚 Redis 库存 + 删除用户标记（允许重新抢购）
            seckillRedisScript.rollbackStock(rollback.getProductId(), rollback.getUserId());
        } catch (Exception e) {
            // DB 已经退过了，这里再重试无法判断 Redis 到底是没执行还是执行后抛错，
            // 重试可能导致 Redis 多 +1（超卖）。所以 ack + ERROR，把差异留给对账。
            log.error("Redis 库存回滚失败，DB 已回滚完成，需人工对账：orderId={}, productId={}, userId={}",
                    rollback.getOrderId(), rollback.getProductId(), rollback.getUserId(), e);
            ack(channel, deliveryTag);
            return;
        }

        ack(channel, deliveryTag);
        log.info("库存回滚成功：orderId={}, productId={}, userId={}, reason={}",
                rollback.getOrderId(), rollback.getProductId(), rollback.getUserId(), rollback.getReason());
    }

    /**
     * 解析消息体。字段可能被 Jackson 反序列化成 Integer / Long / String，统一走字符串转换。
     *
     * @return orderId 或 productId 或 userId 缺失时返回 null（无法安全处理）
     */
    private StockRollbackMessage parse(Map<String, Object> msg) {
        try {
            Long orderId = toLong(msg.get("orderId"));
            Long productId = toLong(msg.get("productId"));
            Long userId = toLong(msg.get("userId"));
            if (orderId == null || productId == null || userId == null) {
                return null;
            }
            StockRollbackMessage rollback = new StockRollbackMessage();
            rollback.setOrderId(orderId);
            rollback.setProductId(productId);
            rollback.setUserId(userId);
            Object orderNo = msg.get("orderNo");
            rollback.setOrderNo(orderNo == null ? null : orderNo.toString());
            Object reason = msg.get("reason");
            rollback.setReason(reason == null ? REASON_CANCEL : reason.toString());
            return rollback;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        return Long.valueOf(value.toString().trim());
    }

    private void ack(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException e) {
            log.error("ACK 失败", e);
        }
    }

    private void nack(Channel channel, long deliveryTag, boolean requeue) {
        try {
            channel.basicNack(deliveryTag, false, requeue);
        } catch (IOException e) {
            log.error("NACK 失败", e);
        }
    }
}
