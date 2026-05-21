package com.example.flashmall.common.util;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class RedisUtil {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    public void set(String key, Object value, long timeout, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, value, timeout, unit);
    }

    public Object get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    public Boolean del(String key) {
        return redisTemplate.delete(key);
    }

    /**
     * Execute a Lua script on Redis.
     * @param script Lua script text
     * @param numKeys number of keys (must be <= keysAndArgs.length)
     * @param keysAndArgs keys followed by args
     * @return deserialized result (Object or List<Object>)
     */
    public Object execLua(String script, int numKeys, Object... keysAndArgs) {
        @SuppressWarnings("unchecked")
        RedisSerializer<Object> serializer = (RedisSerializer<Object>) redisTemplate.getValueSerializer();

        RedisCallback<Object> callback = connection -> {
            // serialize all keys and args
            byte[][] args = new byte[keysAndArgs.length][];
            for (int i = 0; i < keysAndArgs.length; i++) {
                Object v = keysAndArgs[i];
                if (v == null) {
                    args[i] = null;
                } else if (v instanceof byte[]) {
                    args[i] = (byte[]) v;
                } else if (v instanceof String) {
                    args[i] = ((String) v).getBytes(StandardCharsets.UTF_8);
                } else {
                    args[i] = serializer.serialize(v);
                }
            }

            Object raw = connection.eval(script.getBytes(StandardCharsets.UTF_8), ReturnType.VALUE, numKeys, args);

            // Deserialize common return types
            if (raw instanceof byte[]) {
                return serializer.deserialize((byte[]) raw);
            }
            if (raw instanceof List) {
                List<?> rawList = (List<?>) raw;
                List<Object> out = new ArrayList<>(rawList.size());
                for (Object item : rawList) {
                    if (item instanceof byte[]) {
                        out.add(serializer.deserialize((byte[]) item));
                    } else {
                        out.add(item);
                    }
                }
                return out;
            }
            return raw;
        };

        return redisTemplate.execute(callback);
    }

    public <T> RBucket<T> getBucket(String key) {
        return redissonClient.getBucket(key);
    }
}
