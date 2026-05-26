package com.example.seckillservice.script;

import com.example.flashmall.common.util.RedisKeyUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.List;

@Component
@Slf4j
public class SeckillRedisScript {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 执行秒杀 Lua 脚本
     * @return 0-成功，1-库存不足，2-重复抢购
     */
    public Long executeSeckill(Long productId, Long userId) {
        String stockKey = RedisKeyUtil.buildSeckillStockKey(productId);
        String userKey = RedisKeyUtil.buildSeckillUserKey(productId, userId);

        List<String> keys = Arrays.asList(stockKey, userKey);

        // 执行秒杀 Lua 脚本
        return redisTemplate.execute(
                SECKILL_SCRIPT,
                keys,
                String.valueOf(userId),
                String.valueOf(productId)
        );
    }

    /**
     * 回滚库存（订单超时/取消时调用）
     */
    public void rollbackStock(Long productId, Long userId) {
        String stockKey = RedisKeyUtil.buildSeckillStockKey(productId);
        String userKey = RedisKeyUtil.buildSeckillUserKey(productId, userId);

        // 库存加回
        redisTemplate.opsForValue().increment(stockKey, 1);
        // 删除用户标记（允许重新抢购）
        redisTemplate.delete(userKey);
    }
}
