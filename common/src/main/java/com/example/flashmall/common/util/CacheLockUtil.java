package com.example.flashmall.common.util;

import com.example.flashmall.common.entity.NullCache;
import com.example.flashmall.common.entity.Shop;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.io.Serializable;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

//通用互斥锁工具类
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheLockUtil {

    private final RedisTemplate<String, Object> redisTemplate;

    // 标准锁配置（高并发必生效）
    private static final long LOCK_TTL = 10;
    private static final int MAX_RETRY = 200;
    private static final long WAIT_TIME = 200;
    private static final long SPIN_INTERVAL = 10; // 自旋间隔（ms）
    private static final String RELEASE_LOCK_SCRIPT = // 释放锁Lua脚本
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('del', KEYS[1]) else return 0 end"; // 释放锁的Lua脚本

    // 统一缓存读取
    private <T> T getCache(String cacheKey) {
        Object obj = redisTemplate.opsForValue().get(cacheKey);
        if (obj instanceof NullCache) {
            return null;
        }
        return (T) obj;
    }

    // 使用总超时时间替代固定重试次数，在超时前不断尝试读取缓存和获取锁。
    @SuppressWarnings("unchecked")
    public <T> T executeWithLock(String cacheKey, String lockKey, Supplier<T> fallback, Supplier<Long> ttlSupplier) {
        // 1. 第一次快速检查缓存
        T cached = getCache(cacheKey);
        if (cached != null) return cached;

        String lockValue = UUID.randomUUID().toString();
        long deadline = System.currentTimeMillis() + WAIT_TIME;

        while (System.currentTimeMillis() < deadline) {
            // 2. 每次循环优先检查缓存（避免睡眠期间错过）
            cached = getCache(cacheKey);
            if (cached != null) return cached;

            // 3. 尝试获取锁
            Boolean locked = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, lockValue, LOCK_TTL, TimeUnit.SECONDS);

            // 获取锁成功
            if (Boolean.TRUE.equals(locked)) {
                // 双检缓存
                cached = getCache(cacheKey);
                if (cached != null) {
                    // 锁持有人释放锁后返回
                    releaseLock(lockKey, lockValue);
                    return cached;
                }

                // 唯一查库
                T result = fallback.get();

                // 库中存在被查询数据 写缓存
                if (result != null) {
                    redisTemplate.opsForValue().set(cacheKey, result, ttlSupplier.get(), TimeUnit.SECONDS);
                } else {
                    // 空值缓存
                    redisTemplate.opsForValue().set(cacheKey, NullCache.INSTANCE, 60L, TimeUnit.SECONDS);
                }
                return result;
            }

            // 未获取锁，短暂休眠后继续自旋
            try {
                TimeUnit.MILLISECONDS.sleep(SPIN_INTERVAL);// 睡眠后 继续尝试获取锁
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // 中断时再次检查缓存
                cached = getCache(cacheKey);
                if (cached != null) return cached;
                // 此时只能降级（极少发生），但仍先尝试加锁
                releaseLock(lockKey, lockValue); // 安全释放
                return fallback.get();
            }
        }

        // 4. 超时后仍未获得锁或缓存，降级策略：再次尝试读缓存，否则熔断（例如返回默认值或报错）
        cached = getCache(cacheKey);
        if (cached != null) return cached;

        // 若超时且无缓存，说明系统负载过高，可返回 null 或抛出异常，但不要查库
        log.warn("缓存互斥超时，key={}，降级返回null", cacheKey);
        return null; // 或者 throw new ServiceException("服务繁忙");
    }

    // 抽取释放锁方法（使用 Lua 脚本）
    private void releaseLock(String lockKey, String lockValue) {
        redisTemplate.execute(
                new DefaultRedisScript<>(RELEASE_LOCK_SCRIPT, Long.class),
                Collections.singletonList(lockKey),
                lockValue
        );
    }
}