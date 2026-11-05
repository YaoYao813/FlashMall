package com.example.seckillservice.cache;

import com.example.flashmall.common.entity.SeckillEvent;
import com.example.seckillservice.dto.SeckillEventWindow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeckillEventCacheTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private SeckillEventCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        cache = new SeckillEventCache(redisTemplate, new ObjectMapper());
    }

    @Test
    void preheatWritesStockBeforeWindow() throws Exception {
        SeckillEvent event = event();

        cache.preheat(event);

        InOrder order = inOrder(valueOps);
        order.verify(valueOps).set(eq("seckill:stock:7"), eq("30"));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        order.verify(valueOps).set(eq("seckill:event:7"), json.capture());

        SeckillEventWindow window = new ObjectMapper().readValue(json.getValue(), SeckillEventWindow.class);
        assertEquals(9L, window.getEventId());
        assertEquals(cache.toEpochMillis(event.getStartTime()), window.getStartTs());
        assertEquals(cache.toEpochMillis(event.getEndTime()), window.getEndTs());
    }

    @Test
    void readsWindowFromPlainJson() {
        when(valueOps.get("seckill:event:7"))
                .thenReturn("{\"eventId\":9,\"startTs\":100,\"endTs\":200}");

        SeckillEventWindow window = cache.readWindow(7L);

        assertEquals(9L, window.getEventId());
        assertEquals(100L, window.getStartTs());
        assertEquals(200L, window.getEndTs());
    }

    @Test
    void missingWindowReturnsNull() {
        when(valueOps.get(anyString())).thenReturn(null);

        assertNull(cache.readWindow(7L));
    }

    @Test
    void malformedWindowIsTreatedAsNotPreheated() {
        when(valueOps.get("seckill:event:7")).thenReturn("not-json");

        assertNull(cache.readWindow(7L));
    }

    @Test
    void windowWithoutTimesIsTreatedAsNotPreheated() {
        when(valueOps.get("seckill:event:7")).thenReturn("{\"eventId\":9}");

        assertNull(cache.readWindow(7L));
    }

    @Test
    void readsStockAsNumber() {
        when(valueOps.get("seckill:stock:7")).thenReturn("30");

        assertEquals(30, cache.getStock(7L));
    }

    @Test
    void malformedStockReturnsNull() {
        when(valueOps.get("seckill:stock:7")).thenReturn("abc");

        assertNull(cache.getStock(7L));
    }

    @Test
    void deleteWindowRemovesKey() {
        cache.deleteWindow(7L);

        verify(redisTemplate).delete("seckill:event:7");
    }

    private SeckillEvent event() {
        SeckillEvent event = new SeckillEvent();
        event.setId(9L);
        event.setProductId(7L);
        event.setStock(30);
        event.setStartTime(LocalDateTime.of(2026, 11, 6, 10, 0));
        event.setEndTime(LocalDateTime.of(2026, 11, 6, 12, 0));
        return event;
    }
}
