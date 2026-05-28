package com.example.flashmall.order.config;

// RabbitMQ 延迟队列配置（超时取消）
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.naming.Binding;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

@Configuration
public class OrderRabbitMQConfig {
    // ========== 延迟取消订单（死信队列实现） ==========
    // 1. 正常队列：消息在这里等待，过期后转到死信队列
    @Bean
    public Queue orderDelayQueue() {
        Map<String, Object> args = new HashMap<>();
        // 消息过期后转入死信交换机
        args.put("x-dead-letter-exchange", "order.cancel.exchange");
        args.put("x-dead-letter-routing-key", "order.cancel");
        // 消息 TTL 由发送时设置，这里不设固定值
        return new Queue("order.delay.queue", true, false, false, args);
    }
    // 2. 死信队列：真正消费取消订单的队列
    @Bean
    public Queue orderCancelQueue() {
        return new Queue("order.cancel.queue", true);
    }
    // 3. 死信交换机
    @Bean
    public DirectExchange orderCancelExchange() {
        return new DirectExchange("order.cancel.exchange");
    }
    // 4. 绑定：死信队列绑定到死信交换机
    @Bean
    public Binding bindingOrderCancel() {
        return BindingBuilder.bind(orderCancelQueue())
                .to(orderCancelExchange())
                .with("order.cancel");
    }
    // 5. 延迟消息交换机
    @Bean
    public DirectExchange orderDelayExchange() {
        return new DirectExchange("order.delay.exchange");
    }
    // 6. 绑定：延迟队列绑定到延迟交换机
    @Bean
    public Binding bindingOrderDelay() {
        return BindingBuilder.bind(orderDelayQueue())
                .to(orderDelayExchange())
                .with("order.delay.cancel");
    }
}
