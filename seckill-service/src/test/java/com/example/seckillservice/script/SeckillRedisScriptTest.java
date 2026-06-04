package com.example.seckillservice.script;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SeckillRedisScriptTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private SeckillRedisScript seckillRedisScript;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("executeSeckill 返回0表示秒杀成功")
    void testExecuteSeckillSuccess() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenReturn(0L);

        Long result = seckillRedisScript.executeSeckill(1L, 100L);

        assertEquals(0L, result);
        verify(redisTemplate).execute(any(DefaultRedisScript.class), anyList(), eq("100"), eq("1"));
    }

    @Test
    @DisplayName("executeSeckill 返回1表示库存不足")
    void testExecuteSeckillOutOfStock() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenReturn(1L);

        Long result = seckillRedisScript.executeSeckill(1L, 100L);

        assertEquals(1L, result);
    }

    @Test
    @DisplayName("executeSeckill 返回2表示重复秒杀")
    void testExecuteSeckillDuplicate() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenReturn(2L);

        Long result = seckillRedisScript.executeSeckill(1L, 100L);

        assertEquals(2L, result);
    }

    @Test
    @DisplayName("rollbackStock 回滚库存并删除用户标记")
    void testRollbackStock() {
        when(valueOperations.increment(anyString(), eq(1L))).thenReturn(1L);
        when(redisTemplate.delete(anyString())).thenReturn(true);

        seckillRedisScript.rollbackStock(1L, 100L);

        verify(valueOperations).increment("seckill:stock:1", 1);
        verify(redisTemplate).delete("seckill:user:1:100");
    }

    @Test
    @DisplayName("Lua脚本传给Redis的keys和args格式正确")
    void testLuaScriptKeysAndArgs() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenReturn(0L);

        seckillRedisScript.executeSeckill(5L, 99L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(DefaultRedisScript.class), keysCaptor.capture(), eq("99"), eq("5"));

        List<String> keys = keysCaptor.getValue();
        assertEquals("seckill:stock:5", keys.get(0));
        assertEquals("seckill:user:5:99", keys.get(1));
    }
}
