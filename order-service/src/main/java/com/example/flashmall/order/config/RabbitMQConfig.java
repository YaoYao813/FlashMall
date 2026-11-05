package com.example.flashmall.order.config;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 统一消息序列化为 JSON。
 *
 * <p>Spring Boot 在发现 MessageConverter Bean 时会同时装到 RabbitTemplate 和
 * 监听容器工厂上。不配置时默认用 SimpleMessageConverter（Java 序列化），
 * 而 seckill-service 侧用的是 Jackson，取消订单的库存回滚消息会因
 * "Cannot convert from [byte[]] to [Map]" 被直接丢弃。
 */
@Configuration
public class RabbitMQConfig {

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
