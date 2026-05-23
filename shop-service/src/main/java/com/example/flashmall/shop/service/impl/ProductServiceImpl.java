package com.example.flashmall.shop.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.flashmall.common.entity.Product;
import com.example.flashmall.common.util.RedisKeyUtil;
import com.example.flashmall.shop.mapper.ProductMapper;
import com.example.flashmall.shop.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import com.example.flashmall.common.util.CacheLockUtil;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements ProductService {
    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private CacheLockUtil cacheLockUtil;

    @Autowired
    private ProductMapper productMapper;

    private static final long CACHE_TTL = 30 * 60; // 30分钟

    @Override
    public Product getProductById(Long productId) {
        String cacheKey = RedisKeyUtil.getProductKey(productId);
        String lockKey = "lock:product:" + productId;
        return cacheLockUtil.executeWithLock(
                cacheKey,
                lockKey,
                () -> baseMapper.selectById(productId),  // DB 查询回调
                () -> RedisKeyUtil.PRODUCT_TTL_BASE + (long) (Math.random() * 600) // 随机 TTL
        );
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