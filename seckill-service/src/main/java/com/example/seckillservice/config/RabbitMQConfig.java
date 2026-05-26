package com.example.seckillservice.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // ========== 交换机 ==========
    public static final String SECKILL_EXCHANGE = "seckill.direct.exchange";

    // ========== 队列 ==========
    public static final String SECKILL_ORDER_QUEUE = "seckill.order.queue";

    // ========== 路由键 ==========
    public static final String SECKILL_ORDER_ROUTING_KEY = "seckill.order.create";

    @Bean
    public DirectExchange seckillExchange() {
        return new DirectExchange(SECKILL_EXCHANGE, true, false);
    }

    @Bean
    public Queue seckillOrderQueue() {
        return new Queue(SECKILL_ORDER_QUEUE, true, false, false);
    }

    @Bean
    public Binding bindingSeckillOrder() {
        return BindingBuilder
                .bind(seckillOrderQueue())
                .to(seckillExchange())
                .with(SECKILL_ORDER_ROUTING_KEY);
    }

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 配置消费者：手动确认 + JSON 序列化
     */
    @Bean
    public RabbitListenerContainerFactory<?> rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,           // ← ✅ 修正：参数类型正确
            Jackson2JsonMessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);    // ← ✅ 现在类型匹配了
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL); // 手动确认
        factory.setPrefetchCount(1);                        // 每次取 1 条
        return factory;
    }
}
