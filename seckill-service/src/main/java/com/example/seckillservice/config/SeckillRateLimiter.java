package com.example.seckillservice.config;

// Semaphore 限流
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.concurrent.Semaphore;

/**
 * 秒杀限流器：控制同时进入的请求数，保护系统
 */
@Slf4j
@Component
public class SeckillRateLimiter {
    // 最多同时处理 200 个请求（可以根据服务器性能调整）
    private final Semaphore semaphore = new Semaphore(200, true);
    /**
     * 尝试获取许可
     * @return true-可以继续，false-系统繁忙
     */
    public boolean tryAcquire() {
        boolean acquired = semaphore.tryAcquire();
        if (!acquired) {
            log.warn("系统繁忙，限流拦截");
        }
        return acquired;
    }
    /**
     * 释放许可（请求处理完成后必须调用）
     */
    public void release() {
        semaphore.release();
    }
    /**
     * 当前排队请求数
     */
    public int getQueueLength() {
        return semaphore.getQueueLength();
    }
}