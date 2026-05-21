package com.example.flashmall.shop.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 基础配置：交换机、队列、路由键
 */
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME = "seckill.exchange";
    public static final String QUEUE_NAME = "seckill.order.queue";
    public static final String ROUTING_KEY = "seckill.order";

    @Bean
    public DirectExchange seckillExchange() {
        return new DirectExchange(EXCHANGE_NAME, true, false);
    }

    @Bean
    public Queue seckillQueue() {
        return new Queue(QUEUE_NAME, true);
    }

    @Bean
    public Binding seckillBinding(Queue seckillQueue, DirectExchange seckillExchange) {
        return BindingBuilder.bind(seckillQueue).to(seckillExchange).with(ROUTING_KEY);
    }
}

