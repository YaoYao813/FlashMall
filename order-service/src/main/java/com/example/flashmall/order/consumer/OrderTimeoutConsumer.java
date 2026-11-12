package com.example.flashmall.order.consumer;

import com.rabbitmq.client.Channel;
import com.example.flashmall.common.entity.Order;
import com.example.flashmall.common.entity.StockRollbackMessage;
import com.example.flashmall.common.mapper.OrderMapper;
import com.example.flashmall.order.enums.OrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 超时取消消费者。
 *
 * <p>状态变更是 CAS（{@code WHERE status = 0}）：用户可能在延迟消息到期的同一瞬间付款成功，
 * 「先查再改」会让超时线程把已支付的订单改成已超时，并把库存退回去。
 *
 * <p>重投语义（这是最容易写错的地方）：ack 丢失导致消息重投时，CAS 会返回 0，
 * 此时<b>不能直接 ack 了事</b>——上一轮可能正好死在「状态改成功、回滚消息没发出去」之间，
 * 库存就永远退不回去了。所以只要看到订单当前是「已超时」，就把回滚消息再发一次，
 * 由消费端按 orderId 去重。已支付/已取消则什么都不做。
 */
@Component
@Slf4j
public class OrderTimeoutConsumer {
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = "order.cancel.queue")
    public void handleTimeoutCancel(Long orderId, Channel channel,
                                    @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            log.warn("超时取消：订单不存在，跳过：orderId={}", orderId);
            ack(channel, deliveryTag);
            return;
        }

        int affected;
        try {
            affected = orderMapper.casTerminate(orderId, OrderStatus.TIMEOUT.getCode());
        } catch (Exception e) {
            // DB 抖动：重投，等下次
            log.error("超时取消改状态失败，消息重新入队：orderId={}", orderId, e);
            nack(channel, deliveryTag);
            return;
        }

        if (affected == 0) {
            Order latest = orderMapper.selectById(orderId);
            if (latest != null && latest.getStatus() == OrderStatus.TIMEOUT.getCode()) {
                // 上一轮已经改成功，可能没来得及发回滚消息 —— 补发（消费端幂等）
                sendRollback(latest);
                log.info("超时取消重投：补发回滚消息 orderId={}", orderId);
            } else {
                // 已支付 / 已取消：超时消息作废
                log.info("超时取消跳过：订单已是终态 status={}, orderId={}",
                        latest == null ? "已删除" : latest.getStatus(), orderId);
            }
            ack(channel, deliveryTag);
            return;
        }

        sendRollback(order);
        ack(channel, deliveryTag);
        log.info("订单超时取消：orderId={}", orderId);
    }

    /**
     * 发送回滚消息。失败时抛出，让上层 nack 重投——
     * 重投后 CAS 返回 0、状态已是 TIMEOUT，会走「补发回滚消息」分支。
     */
    private void sendRollback(Order order) {
        rabbitTemplate.convertAndSend(
                "seckill.direct.exchange",
                "seckill.stock.rollback",
                StockRollbackMessage.of(order, "TIMEOUT")
        );
    }

    private void ack(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException e) {
            log.error("ACK失败", e);
        }
    }

    private void nack(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (IOException e) {
            log.error("NACK失败", e);
        }
    }
}
