package com.example.flashmall.shop.BloomFilterLoader;

import com.example.flashmall.common.util.BloomFilterUtil;
import com.example.flashmall.shop.mapper.ShopMapper;
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
 * 店铺布隆过滤器数据加载器
 * 仅负责加载店铺ID，与商品加载完全独立
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ShopBloomFilterLoader implements ApplicationRunner {

    private final BloomFilterUtil bloomFilterUtil;
    private final ShopMapper shopMapper;
    private final RedissonClient redissonClient;

    // 独立的加载锁，不影响商品加载
    private static final String LOAD_LOCK = "bloom:load:shop:lock";
    private static final int MAX_WAIT = 10;

    @Override
    public void run(ApplicationArguments args) {
        RLock lock = redissonClient.getLock(LOAD_LOCK);
        try {
            // 抢不到锁等待10秒，确保数据加载完成
            if (lock.tryLock(MAX_WAIT, TimeUnit.SECONDS)) {
                try {
                    log.info("开始加载店铺ID到布隆过滤器");
                    List<Long> shopIds = shopMapper.selectAllIds();
                    bloomFilterUtil.addAllShopIds(shopIds);
                    log.info("店铺ID加载完成，共 {} 条", shopIds.size());
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            } else {
                log.info("其他实例正在加载店铺ID，跳过");
            }
        } catch (Exception e) {
            log.error("加载店铺ID失败，服务继续启动", e);
        }
    }
}
