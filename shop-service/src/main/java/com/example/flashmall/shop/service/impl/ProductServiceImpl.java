package com.example.flashmall.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.flashmall.common.entity.Product;
import com.example.flashmall.common.util.PageResult;
import com.example.flashmall.common.util.RedisKeyUtil;
import com.example.flashmall.common.mapper.ProductMapper;
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
    public PageResult<Product> listProducts(int page, int size, String keyword, String sort) {
        QueryWrapper<Product> wrapper = new QueryWrapper<>();
        wrapper.eq("status", 1);

        if (keyword != null && !keyword.trim().isEmpty()) {
            wrapper.like("title", keyword.trim());
        }

        switch (sort) {
            case "price_asc":
                wrapper.orderByAsc("price");
                break;
            case "price_desc":
                wrapper.orderByDesc("price");
                break;
            case "create_time_asc":
                wrapper.orderByAsc("create_time");
                break;
            default:
                wrapper.orderByDesc("create_time");
                break;
        }

        Page<Product> mpPage = new Page<>(page, size);
        Page<Product> result = baseMapper.selectPage(mpPage, wrapper);

        return new PageResult<>(result.getRecords(), result.getTotal(), result.getPages());
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