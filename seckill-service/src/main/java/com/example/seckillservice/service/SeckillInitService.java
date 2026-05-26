package com.example.seckillservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.flashmall.common.entity.ProductStock;
import com.example.flashmall.common.mapper.ProductStockMapper;
import com.example.flashmall.common.util.RedisKeyUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.util.List;

@Slf4j
@Service
// 秒杀前预热商品信息到缓存
public class SeckillInitService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ProductStockMapper productStockMapper;

    /**
     * 预热单个商品的秒杀库存到 Redis
     * 只有 Redis 中不存在时才写入，避免覆盖正在进行的秒杀数据
     */
    public void initStock(Long productId) {
        String is_exists_key = RedisKeyUtil.buildSeckillStockKey(productId);
        // 关键判断：缓存已存在则不覆盖
        Boolean exists = redisTemplate.hasKey(is_exists_key);
        if (Boolean.TRUE.equals(exists)) {
            log.info("秒杀库存已存在，跳过预热：productId={}", productId);
            return;
        }

        ProductStock stock = productStockMapper.selectOne(
                new LambdaQueryWrapper<ProductStock>()
                        .eq(ProductStock::getProductId, productId)
        );
        if (stock != null) {
            String key = RedisKeyUtil.buildSeckillStockKey(productId);
            redisTemplate.opsForValue().set(key, stock.getStock());
            log.info("秒杀库存预热：productId={}, stock={}", productId, stock.getStock());
        } else {
            log.warn("商品库存不存在：productId={}", productId);
        }
    }
    /**
     * 批量预热多个商品的秒杀库存
     */
    public void initStockBatch(List<Long> productIds) {
        for (Long productId : productIds) {
            initStock(productId);
        }
    }
    /**
     * 预热所有有库存的商品（项目启动时调用）
     */
    public void initAllStock() {
        List<ProductStock> allStocks = productStockMapper.selectList(null);// 所有商品
        int initCount = 0;
        for (ProductStock stock : allStocks) {
            String key = RedisKeyUtil.buildSeckillStockKey(stock.getProductId());

            if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
                // 缓存已存在，跳过（可能是正在秒杀的商品）
                continue;
            }
            // 缓存不存在，写入
            redisTemplate.opsForValue().set(key, stock.getStock());
            initCount++;
        }
        log.info("秒杀库存预热完成：初始化 {} 个", initCount);
    }

    /**
     * 强制覆盖缓存中的秒杀库存（仅运营后台手动调用）
     */
    public void forceInitStock(Long productId) {
        String key = RedisKeyUtil.buildSeckillStockKey(productId);
        ProductStock stock = productStockMapper.selectOne(
                new LambdaQueryWrapper<ProductStock>()
                        .eq(ProductStock::getProductId, productId)
        );
        if (stock != null) {
            redisTemplate.opsForValue().set(key, stock.getStock());
            log.warn("强制覆盖秒杀库存：productId={}, stock={}", productId, stock.getStock());
        }
    }
}
