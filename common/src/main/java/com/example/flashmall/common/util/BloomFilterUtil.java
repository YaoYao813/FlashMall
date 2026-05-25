package com.example.flashmall.common.util;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;

/**
 * 分布式布隆过滤器工具类
 * 专门处理店铺和商品ID的存在性判断，防缓存穿透
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BloomFilterUtil {

    @Autowired
    private RedissonClient redissonClient; //redission客户端

    // 过滤器名称（全局唯一）
    private static final String SHOP_BLOOM_FILTER = "bloom:shop:id";
    private static final String PRODUCT_BLOOM_FILTER = "bloom:product:id";

    // 配置参数
    private static final long SHOP_EXPECTED = 10_000L;
    private static final long PRODUCT_EXPECTED = 100_000L;
    private static final double FALSE_PROBABILITY = 0.01;

    private RBloomFilter<Long> shopBloomFilter;
    private RBloomFilter<Long> productBloomFilter;

    @Value("${bloom.filter.enabled:true}")
    private boolean bloomFilterEnabled;

    @PostConstruct
    public void init() {
        // 初始化店铺过滤器（只有不存在时才创建，不覆盖已有数据）
        shopBloomFilter = redissonClient.getBloomFilter(SHOP_BLOOM_FILTER);
        if (!shopBloomFilter.isExists()) {
            shopBloomFilter.tryInit(SHOP_EXPECTED, FALSE_PROBABILITY);
            log.info("店铺ID布隆过滤器已创建");
        } else {
            log.info("店铺ID布隆过滤器已存在，当前元素数：{}", shopBloomFilter.count());
        }

        // 初始化商品过滤器
        productBloomFilter = redissonClient.getBloomFilter(PRODUCT_BLOOM_FILTER);
        if (!productBloomFilter.isExists()) {
            productBloomFilter.tryInit(PRODUCT_EXPECTED, FALSE_PROBABILITY);
            log.info("商品ID布隆过滤器已创建");
        } else {
            log.info("商品ID布隆过滤器已存在，当前元素数：{}", productBloomFilter.count());
        }
    }


    // ====================== 店铺相关操作 ======================
    public void addAllShopIds(List<Long> ids) {
        for (Long id : ids) {
            shopBloomFilter.add(id);
        }
        log.info("已加载 {} 个店铺ID到布隆过滤器", ids.size());
    }

    public boolean mightContainShopId(Long shopId) {
        if (!bloomFilterEnabled) {
            // 关闭时直接返回true，放行所有请求
            return true;
        }
        return shopBloomFilter.contains(shopId);
    }

    public void addShopId(Long shopId) {
        shopBloomFilter.add(shopId);
    }


    // ====================== 商品相关操作 ======================
    public void addAllProductIds(List<Long> ids) {
        for (Long id : ids) {
            productBloomFilter.add(id);
        }
        log.info("已加载 {} 个商品ID到布隆过滤器", ids.size());
    }

    public boolean mightContainProductId(Long productId) {
        if (!bloomFilterEnabled) {
            return true;
        }
        return productBloomFilter.contains(productId);
    }

    public void addProductId(Long productId) {
        productBloomFilter.add(productId);
    }
}