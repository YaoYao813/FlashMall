package com.example.seckillservice.consumer;

import com.example.flashmall.common.entity.SeckillMessage;
import com.example.seckillservice.script.SeckillRedisScript;
import com.example.seckillservice.service.SeckillOrderBatchService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillConsumerTest {

    private static final long DELIVERY_TAG = 42L;

    @Mock
    private SeckillOrderBatchService seckillOrderBatchService;
    @Mock
    private SeckillRedisScript seckillRedisScript;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;
    @Mock
    private Channel channel;

    @InjectMocks
    private SeckillConsumer seckillConsumer;

    @Test
    @DisplayName("全部成功：批量 ACK")
    void testAllSuccess() throws Exception {
        givenLockAcquired();
        List<SeckillMessage> messages = Arrays.asList(
                new SeckillMessage(1L, 7L, "NO-1"),
                new SeckillMessage(2L, 8L, "NO-2"));

        seckillConsumer.handleSeckillOrder(messages, channel, DELIVERY_TAG);

        verify(channel).basicAck(DELIVERY_TAG, true);
        verify(channel, never()).basicNack(anyLong(), any(Boolean.class), any(Boolean.class));
        verify(seckillRedisScript, never()).rollbackStock(anyLong(), anyLong());
    }

    @Test
    @DisplayName("一个分组失败：只回补该组 Redis，整条投递进死信队列")
    void testPartialFailure() throws Exception {
        givenLockAcquired();
        doThrow(new RuntimeException("乐观锁冲突"))
                .when(seckillOrderBatchService).processBatchOrders(eq(7L), any());
        List<SeckillMessage> messages = Arrays.asList(
                new SeckillMessage(1L, 7L, "NO-1"),
                new SeckillMessage(2L, 7L, "NO-2"),
                new SeckillMessage(3L, 8L, "NO-3"));

        seckillConsumer.handleSeckillOrder(messages, channel, DELIVERY_TAG);

        // 只回补失败的 7，不碰已成功的 8
        verify(seckillRedisScript).rollbackStock(7L, 1L);
        verify(seckillRedisScript).rollbackStock(7L, 2L);
        verify(seckillRedisScript, never()).rollbackStock(eq(8L), anyLong());
        // requeue=false → 走死信
        verify(channel).basicNack(DELIVERY_TAG, true, false);
        verify(channel, never()).basicAck(DELIVERY_TAG, true);
    }

    @Test
    @DisplayName("抢锁超时：整条投递重新入队，不做业务处理")
    void testLockTimeout() throws Exception {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        List<SeckillMessage> messages = Arrays.asList(
                new SeckillMessage(1L, 7L, "NO-1"),
                new SeckillMessage(2L, 8L, "NO-2"));

        seckillConsumer.handleSeckillOrder(messages, channel, DELIVERY_TAG);

        verify(seckillOrderBatchService, never()).processBatchOrders(anyLong(), any());
        verify(channel).basicNack(DELIVERY_TAG, true, true);
        verify(seckillRedisScript, never()).rollbackStock(anyLong(), anyLong());
    }

    private void givenLockAcquired() throws InterruptedException {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }
}
