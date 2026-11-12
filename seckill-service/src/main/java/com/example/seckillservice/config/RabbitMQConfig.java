package com.example.seckillservice.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 工作原理：
// 当生产者发送一条消息到 seckill.direct.exchange交换机
// 并且消息的路由键是 seckill.order.create时
// 交换机就会把这条消息精确地路由到 seckill.order.queue队列中

@Configuration
public class RabbitMQConfig {

    // ========== 秒杀交换机 ==========
    public static final String SECKILL_EXCHANGE = "seckill.direct.exchange";

    // ========== 秒杀队列 ==========
    public static final String SECKILL_ORDER_QUEUE = "seckill.order.queue";

    // ========== 秒杀路由键 ==========
    public static final String SECKILL_ORDER_ROUTING_KEY = "seckill.order.create";

    // ========== 库存回滚队列 ==========
    public static final String SECKILL_STOCK_ROLLBACK_QUEUE = "seckill.stock.rollback.queue";

    // ========== 死信交换机 / 死信队列 / 死信路由键 ==========
    public static final String SECKILL_DLX = "seckill.dlx";
    public static final String SECKILL_ORDER_DLQ = "seckill.order.dlq";
    public static final String SECKILL_ORDER_DEAD_ROUTING_KEY = "seckill.order.dead";

    // 秒杀交换机
    @Bean
    public DirectExchange seckillExchange() {
        return new DirectExchange(SECKILL_EXCHANGE, true, false);
    }

    // 死信交换机：主队列消费失败（basicNack requeue=false）时消息落到这里
    @Bean
    public DirectExchange seckillDlx() {
        return new DirectExchange(SECKILL_DLX, true, false);
    }

    // 秒杀队列。带上死信参数：消息被 basicNack(requeue=false) 后会进 seckill.order.dlq，
    // 不再像以前那样直接丢弃。注意队列参数不可变更，改这里必须删掉旧队列再重启。
    @Bean
    public Queue seckillOrderQueue() {
        return QueueBuilder.durable(SECKILL_ORDER_QUEUE)
                .withArgument("x-dead-letter-exchange", SECKILL_DLX)
                .withArgument("x-dead-letter-routing-key", SECKILL_ORDER_DEAD_ROUTING_KEY)
                .build();
    }

    // 死信队列：不配消费者，只做落盘等人工排查/重放
    @Bean
    public Queue seckillOrderDlq() {
        return QueueBuilder.durable(SECKILL_ORDER_DLQ).build();
    }

    @Bean
    public Binding bindingSeckillOrderDlq() {
        return BindingBuilder
                .bind(seckillOrderDlq())
                .to(seckillDlx())
                .with(SECKILL_ORDER_DEAD_ROUTING_KEY);
    }

    // 建立队列和交换机之间的绑定关系，指定路由规则
    @Bean
    public Binding bindingSeckillOrder() {
        return BindingBuilder
                .bind(seckillOrderQueue())
                .to(seckillExchange())
                .with(SECKILL_ORDER_ROUTING_KEY);
    }

    // 消息转换器：使用 Jackson 将消息转换为 JSON 格式，方便跨语言通信
    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 配置消费者：批量监听 + 手动确认 + JSON 序列化
     */
    @Bean
    public RabbitListenerContainerFactory<?> rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL); // 手动确认
        factory.setBatchListener(true);          // 启用批量监听，一次接收多条消息
        factory.setBatchSize(50);                // 每批最多 50 条
        factory.setReceiveTimeout(1000L);        // 超过 1 秒凑不够 50 条也立即提交
        factory.setConcurrentConsumers(10);
        factory.setMaxConcurrentConsumers(20);
        return factory;
    }

    @Bean
    public Queue stockRollbackQueue() {
        return new Queue(SECKILL_STOCK_ROLLBACK_QUEUE, true);
    }

    @Bean
    public Binding bindingStockRollback() {
        return BindingBuilder.bind(stockRollbackQueue())
                .to(seckillExchange())
                .with("seckill.stock.rollback");
    }
}
