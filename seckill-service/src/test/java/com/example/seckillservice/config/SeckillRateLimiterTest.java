package com.example.seckillservice.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SeckillRateLimiterTest {

    @Test
    @DisplayName("获取和释放许可正常")
    void testAcquireAndRelease() {
        SeckillRateLimiter limiter = new SeckillRateLimiter();
        assertTrue(limiter.tryAcquire());
        limiter.release();
        assertTrue(limiter.tryAcquire());
        limiter.release();
    }

    @Test
    @DisplayName("耗尽200个许可后返回false")
    void testExhausted() {
        SeckillRateLimiter limiter = new SeckillRateLimiter();
        for (int i = 0; i < 200; i++) {
            assertTrue(limiter.tryAcquire(), "第" + i + "次获取应该成功");
        }
        assertFalse(limiter.tryAcquire(), "耗尽后应返回false");
        limiter.release();
        assertTrue(limiter.tryAcquire(), "释放后应可再次获取");
        limiter.release();
    }

    @Test
    @DisplayName("并发场景下许可数不超过200")
    void testConcurrentAcquire() throws InterruptedException {
        SeckillRateLimiter limiter = new SeckillRateLimiter();
        int threadCount = 300;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                if (limiter.tryAcquire()) {
                    successCount.incrementAndGet();
                } else {
                    failCount.incrementAndGet();
                }
                latch.countDown();
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(200, successCount.get());
        assertEquals(100, failCount.get());
    }

    @Test
    @DisplayName("队列长度初始为0")
    void testQueueLength() {
        SeckillRateLimiter limiter = new SeckillRateLimiter();
        assertEquals(0, limiter.getQueueLength());
    }
}
