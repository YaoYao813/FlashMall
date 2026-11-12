package com.example.seckillservice.consumer;

import com.example.flashmall.common.entity.StockRollbackMessage;
import com.example.seckillservice.script.SeckillRedisScript;
import com.example.seckillservice.service.StockRollbackService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockRollbackConsumerTest {

    private static final long DELIVERY_TAG = 7L;

    @Mock
    private StockRollbackService stockRollbackService;
    @Mock
    private SeckillRedisScript seckillRedisScript;
    @Mock
    private Channel channel;

    @InjectMocks
    private StockRollbackConsumer consumer;

    @Test
    @DisplayName("正常回滚：DB 先、Redis 后，然后 ACK")
    void testHappyPath() throws Exception {
        when(stockRollbackService.rollbackOnce(any(StockRollbackMessage.class))).thenReturn(true);

        consumer.handleStockRollback(body(), channel, DELIVERY_TAG);

        verify(stockRollbackService).rollbackOnce(any(StockRollbackMessage.class));
        verify(seckillRedisScript).rollbackStock(7L, 9001L);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("重复回滚：只 ACK，绝不碰 Redis")
    void testDuplicateDoesNotTouchRedis() throws Exception {
        when(stockRollbackService.rollbackOnce(any(StockRollbackMessage.class))).thenReturn(false);

        consumer.handleStockRollback(body(), channel, DELIVERY_TAG);

        verify(seckillRedisScript, never()).rollbackStock(anyLong(), anyLong());
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("DB 写失败：重新入队，不 ACK")
    void testDbFailureRequeues() throws Exception {
        when(stockRollbackService.rollbackOnce(any(StockRollbackMessage.class)))
                .thenThrow(new RuntimeException("db down"));

        consumer.handleStockRollback(body(), channel, DELIVERY_TAG);

        verify(channel).basicNack(DELIVERY_TAG, false, true);
        verify(seckillRedisScript, never()).rollbackStock(anyLong(), anyLong());
    }

    @Test
    @DisplayName("Redis 失败：DB 已回滚，ACK 掉并留给对账")
    void testRedisFailureAcks() throws Exception {
        when(stockRollbackService.rollbackOnce(any(StockRollbackMessage.class))).thenReturn(true);
        doThrow(new RuntimeException("redis down"))
                .when(seckillRedisScript).rollbackStock(eq(7L), eq(9001L));

        consumer.handleStockRollback(body(), channel, DELIVERY_TAG);

        // 不能重投：Redis 侧没有幂等保护，重投可能多 +1（超卖）
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), eq(false), eq(true));
    }

    @Test
    @DisplayName("老格式消息（缺 orderId）：不处理，打 ERROR 后 ACK")
    void testLegacyMessageDropped() throws Exception {
        Map<String, Object> legacy = new HashMap<>();
        legacy.put("productId", 7);
        legacy.put("userId", 9001);

        consumer.handleStockRollback(legacy, channel, DELIVERY_TAG);

        verify(stockRollbackService, never()).rollbackOnce(any(StockRollbackMessage.class));
        verify(seckillRedisScript, never()).rollbackStock(anyLong(), anyLong());
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    private Map<String, Object> body() {
        Map<String, Object> msg = new HashMap<>();
        msg.put("orderId", 1001);
        msg.put("orderNo", "20261112200000123456789");
        msg.put("productId", 7);
        msg.put("userId", 9001);
        msg.put("reason", "CANCEL");
        return msg;
    }
}
