package com.example.flashmall.order.consumer;

import com.rabbitmq.client.Channel;
import com.example.flashmall.common.entity.Order;
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
import java.util.HashMap;
import java.util.Map;

// 超时取消消费者
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
        try {
            Order order = orderMapper.selectById(orderId);
            if (order == null || order.getStatus() != OrderStatus.PENDING_PAY.getCode()) {
                // 已支付或已取消，不需处理
                channel.basicAck(deliveryTag, false);
                return;
            }
            // 超时取消
            order.setStatus(OrderStatus.TIMEOUT.getCode());
            orderMapper.updateById(order);
            // 回滚库存
            Map<String, Object> msg = new HashMap<>();
            msg.put("productId", order.getProductId());
            msg.put("userId", order.getUserId());
            rabbitTemplate.convertAndSend(
                    "seckill.direct.exchange",
                    "seckill.stock.rollback",
                    msg
            );
            channel.basicAck(deliveryTag, false);
            log.info("订单超时取消：orderId={}", orderId);
        } catch (Exception e) {
            try {
                channel.basicNack(deliveryTag, false, true);
            } catch (IOException ex) {
                log.error("ACK失败", ex);
            }
        }
    }
}
