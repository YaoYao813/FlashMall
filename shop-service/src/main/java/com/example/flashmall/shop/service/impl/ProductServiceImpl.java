package com.example.flashmall.shop.service.impl;

import java.util.concurrent.TimeUnit;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.flashmall.common.entity.Product;
import com.example.flashmall.common.util.RedisKeyUtil;
import com.example.flashmall.shop.mapper.ProductMapper;
import com.example.flashmall.shop.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements ProductService {
    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private ProductMapper productMapper;

    private static final long CACHE_TTL = 30 * 60; // 30分钟

    @Override
    public Product getProductById(Long productId) {
        String key = RedisKeyUtil.getProductKey(productId);
        // 1. 查询缓存
        Product product = (Product) redisTemplate.opsForValue().get(key);
        if (product != null) {
            return product;
        }
        // 2. 缓存未命中，查数据库
        product = productMapper.selectById(productId);
        if (product != null) {
            // 3. 写入缓存（加随机过期时间防雪崩）
            long ttl = CACHE_TTL + (long)(Math.random() * 300); // 额外随机0~5分钟
            redisTemplate.opsForValue().set(key, product, ttl, TimeUnit.SECONDS); //写入缓存
        } else { // 找不到product
            // 4. 防穿透：缓存空值（5分钟）
            redisTemplate.opsForValue().set(key, new Product(), 300, TimeUnit.SECONDS);
        }
        return product;
    }

    @Override
    public boolean updateProduct(Product product) {
        // 1. 更新数据库
        int result = productMapper.updateById(product);
        // 2. 删除缓存（不管更新是否成功，都删，因为数据可能变了）
        String key = RedisKeyUtil.getProductKey(product.getId());
        redisTemplate.delete(key);
        return result > 0;
    }
}