package com.example.seckillservice.cache;

import com.example.flashmall.common.entity.SeckillEvent;
import com.example.flashmall.common.util.RedisKeyUtil;
import com.example.seckillservice.dto.SeckillEventWindow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 秒杀活动在 Redis 侧的读写入口：库存 + 活动窗口。
 *
 * <p>用 StringRedisTemplate 而非 RedisTemplate&lt;String,Object&gt;：
 * 库存必须是裸数字（Lua 直接 tonumber），窗口 JSON 要能在 redis-cli 里直接看懂，
 * 不能被 Jackson 写入 @class 类型信息。
 *
 * <p>活动时间按项目约定统一按 UTC+8 解释；显式指定时区，不依赖容器 TZ。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillEventCache {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 预热：先写库存，再写窗口。顺序不能反——窗口是秒杀入口的开关，
     * 只要窗口存在，库存一定已经就位。
     */
    public void preheat(SeckillEvent event) {
        setStock(event.getProductId(), event.getStock());
        writeWindow(event);
    }

    public void setStock(Long productId, Integer stock) {
        stringRedisTemplate.opsForValue()
                .set(RedisKeyUtil.buildSeckillStockKey(productId), String.valueOf(stock));
    }

    public void writeWindow(SeckillEvent event) {
        SeckillEventWindow window = new SeckillEventWindow(
                event.getId(),
                toEpochMillis(event.getStartTime()),
                toEpochMillis(event.getEndTime()));
        try {
            stringRedisTemplate.opsForValue()
                    .set(RedisKeyUtil.buildSeckillEventKey(event.getProductId()),
                            objectMapper.writeValueAsString(window));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("活动窗口序列化失败", e);
        }
    }

    public void deleteWindow(Long productId) {
        stringRedisTemplate.delete(RedisKeyUtil.buildSeckillEventKey(productId));
    }

    public boolean windowExists(Long productId) {
        return Boolean.TRUE.equals(
                stringRedisTemplate.hasKey(RedisKeyUtil.buildSeckillEventKey(productId)));
    }

    /**
     * 读活动窗口。不存在或内容损坏都视为未预热，由调用方返回统一提示。
     */
    public SeckillEventWindow readWindow(Long productId) {
        String json = stringRedisTemplate.opsForValue()
                .get(RedisKeyUtil.buildSeckillEventKey(productId));
        if (json == null) {
            return null;
        }
        try {
            SeckillEventWindow window = objectMapper.readValue(json, SeckillEventWindow.class);
            if (window.getStartTs() == null || window.getEndTs() == null) {
                log.warn("活动窗口缺少时间字段：productId={}, json={}", productId, json);
                return null;
            }
            return window;
        } catch (JsonProcessingException e) {
            log.warn("活动窗口解析失败，按未预热处理：productId={}, json={}", productId, json, e);
            return null;
        }
    }

    public Integer getStock(Long productId) {
        String value = stringRedisTemplate.opsForValue()
                .get(RedisKeyUtil.buildSeckillStockKey(productId));
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            log.warn("秒杀库存不是合法数字：productId={}, value={}", productId, value);
            return null;
        }
    }

    public Long incrementStock(Long productId, long delta) {
        return stringRedisTemplate.opsForValue()
                .increment(RedisKeyUtil.buildSeckillStockKey(productId), delta);
    }

    public long toEpochMillis(LocalDateTime time) {
        return time.atZone(BUSINESS_ZONE).toInstant().toEpochMilli();
    }
}
