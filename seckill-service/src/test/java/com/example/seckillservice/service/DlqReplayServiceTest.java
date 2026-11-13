package com.example.seckillservice.service;

import com.example.seckillservice.config.RabbitMQConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DlqReplayServiceTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private DlqReplayService dlqReplayService;

    @Test
    @DisplayName("把死信原样投回主交换机")
    void testReplay() {
        when(rabbitTemplate.receive(RabbitMQConfig.SECKILL_ORDER_DLQ))
                .thenReturn(message(), message(), null);

        Map<String, Object> result = dlqReplayService.replay(10);

        assertEquals(2, result.get("replayed"));
        assertEquals(0, result.get("failed"));
        verify(rabbitTemplate, times(2)).send(
                eq(RabbitMQConfig.SECKILL_EXCHANGE),
                eq(RabbitMQConfig.SECKILL_ORDER_ROUTING_KEY), any(Message.class));
    }

    @Test
    @DisplayName("重放失败时投回死信交换机，不丢消息")
    void testReplayFailureGoesBackToDlq() {
        when(rabbitTemplate.receive(RabbitMQConfig.SECKILL_ORDER_DLQ)).thenReturn(message());
        org.mockito.Mockito.doThrow(new RuntimeException("broker down"))
                .when(rabbitTemplate).send(
                        eq(RabbitMQConfig.SECKILL_EXCHANGE), anyString(), any(Message.class));

        Map<String, Object> result = dlqReplayService.replay(1);

        assertEquals(0, result.get("replayed"));
        assertEquals(1, result.get("failed"));
        verify(rabbitTemplate).send(
                eq(RabbitMQConfig.SECKILL_DLX),
                eq(RabbitMQConfig.SECKILL_ORDER_DEAD_ROUTING_KEY), any(Message.class));
    }

    private Message message() {
        return new Message("{}".getBytes(), new MessageProperties());
    }
}
