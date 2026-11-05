package com.example.seckillservice.service.impl;

import com.example.flashmall.common.entity.SeckillMessage;
import com.example.seckillservice.cache.SeckillEventCache;
import com.example.seckillservice.config.RabbitMQConfig;
import com.example.seckillservice.dto.SeckillEventWindow;
import com.example.seckillservice.script.SeckillRedisScript;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SeckillServiceImplTest {

    @Mock
    private SeckillRedisScript seckillRedisScript;

    @Mock
    private org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;

    @Mock
    private SeckillEventCache seckillEventCache;

    @InjectMocks
    private SeckillServiceImpl seckillService;

    @Test
    @DisplayName("秒杀成功：Lua返回0，生成订单号，发送MQ消息")
    void testSeckillSuccess() {
        openWindow();
        when(seckillRedisScript.executeSeckill(1L, 100L)).thenReturn(0L);

        String orderNo = seckillService.seckill(100L, 1L);

        assertNotNull(orderNo);
        verify(seckillRedisScript).executeSeckill(1L, 100L);
    }

    @Test
    @DisplayName("库存不足：Lua返回1，抛出异常")
    void testSeckillOutOfStock() {
        openWindow();
        when(seckillRedisScript.executeSeckill(1L, 100L)).thenReturn(1L);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> seckillService.seckill(100L, 1L));

        assertEquals("库存不足", ex.getMessage());
    }

    @Test
    @DisplayName("重复秒杀：Lua返回2，抛出异常")
    void testSeckillDuplicate() {
        openWindow();
        when(seckillRedisScript.executeSeckill(1L, 100L)).thenReturn(2L);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> seckillService.seckill(100L, 1L));

        assertEquals("您已抢购过该商品，请勿重复下单", ex.getMessage());
    }

    @Test
    @DisplayName("MQ发送失败时回滚Redis库存")
    void testMqFailureRollsBackRedis() {
        openWindow();
        when(seckillRedisScript.executeSeckill(1L, 100L)).thenReturn(0L);

        doThrow(new AmqpException("连接失败"))
                .when(rabbitTemplate)
                .convertAndSend(eq(RabbitMQConfig.SECKILL_EXCHANGE),
                        eq(RabbitMQConfig.SECKILL_ORDER_ROUTING_KEY),
                        any(SeckillMessage.class));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> seckillService.seckill(100L, 1L));

        assertEquals("系统繁忙，请稍后重试", ex.getMessage());
        verify(seckillRedisScript).rollbackStock(1L, 100L);
    }

    @Test
    @DisplayName("不同用户秒杀不同商品应生成不同订单号")
    void testOrderNoUniqueness() {
        openWindow();
        when(seckillRedisScript.executeSeckill(anyLong(), anyLong())).thenReturn(0L);

        String no1 = seckillService.seckill(100L, 1L);
        String no2 = seckillService.seckill(200L, 2L);

        assertNotNull(no1);
        assertNotNull(no2);
        assertNotEquals(no1, no2);
    }

    @Test
    @DisplayName("活动未预热：不调用 Lua")
    void testEventNotPreheated() {
        when(seckillEventCache.readWindow(1L)).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> seckillService.seckill(100L, 1L));

        assertEquals("活动不存在或未预热", ex.getMessage());
        verifyNoInteractions(seckillRedisScript);
    }

    @Test
    @DisplayName("活动未开始：不调用 Lua")
    void testEventNotStarted() {
        long now = System.currentTimeMillis();
        when(seckillEventCache.readWindow(1L))
                .thenReturn(new SeckillEventWindow(9L, now + 60_000, now + 3_600_000));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> seckillService.seckill(100L, 1L));

        assertEquals("活动未开始", ex.getMessage());
        verifyNoInteractions(seckillRedisScript);
    }

    @Test
    @DisplayName("活动已结束：不调用 Lua")
    void testEventEnded() {
        long now = System.currentTimeMillis();
        when(seckillEventCache.readWindow(1L))
                .thenReturn(new SeckillEventWindow(9L, now - 3_600_000, now - 60_000));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> seckillService.seckill(100L, 1L));

        assertEquals("活动已结束", ex.getMessage());
        verifyNoInteractions(seckillRedisScript);
    }

    private void openWindow() {
        long now = System.currentTimeMillis();
        when(seckillEventCache.readWindow(anyLong()))
                .thenReturn(new SeckillEventWindow(9L, now - 60_000, now + 3_600_000));
    }
}
