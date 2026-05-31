package com.example.flashmall.order.config;

// RabbitMQ 延迟队列配置（超时取消 — 使用延迟消息插件）
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class OrderRabbitMQConfig {

    // 延迟交换机（需要安装 rabbitmq_delayed_message_exchange 插件）
    @Bean
    public CustomExchange orderDelayExchange() {
        Map<String, Object> args = new HashMap<>();
        // 延迟结束后按 direct 类型路由到绑定队列
        args.put("x-delayed-type", "direct");
        return new CustomExchange("order.delay.exchange", "x-delayed-message", true, false, args);
    }

    // 消费队列：延迟结束后消息投递到这里
    @Bean
    public Queue orderCancelQueue() {
        return new Queue("order.cancel.queue", true);
    }

    // 绑定：消费队列绑定到延迟交换机，路由键 order.cancel
    @Bean
    public Binding bindingOrderCancel() {
        return BindingBuilder.bind(orderCancelQueue())
                .to(orderDelayExchange())
                .with("order.cancel")
                .noargs();
    }
}
