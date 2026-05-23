package com.example.flashmall.shop.BloomFilterLoader;

import com.example.flashmall.common.util.BloomFilterUtil;
import com.example.flashmall.shop.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 商品布隆过滤器数据加载器
 * 仅负责加载商品ID，与店铺加载完全独立
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ProductBloomFilterLoader implements ApplicationRunner {

    private final BloomFilterUtil bloomFilterUtil;
    private final ProductMapper productMapper;
    private final RedissonClient redissonClient;

    // 独立的加载锁，不影响店铺加载
    private static final String LOAD_LOCK = "bloom:load:product:lock";
    private static final int MAX_WAIT = 10;

    @Override
    public void run(ApplicationArguments args) {
        RLock lock = redissonClient.getLock(LOAD_LOCK);
        try {
            if (lock.tryLock(MAX_WAIT, TimeUnit.SECONDS)) {
                try {
                    log.info("开始加载商品ID到布隆过滤器");
                    List<Long> productIds = productMapper.selectAllIds();
                    bloomFilterUtil.addAllProductIds(productIds);
                    log.info("商品ID加载完成，共 {} 条", productIds.size());
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            } else {
                log.info("其他实例正在加载商品ID，跳过");
            }
        } catch (Exception e) {
            log.error("加载商品ID失败，服务继续启动", e);
        }
    }
}
