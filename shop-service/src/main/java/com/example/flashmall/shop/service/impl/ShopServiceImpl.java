package com.example.flashmall.shop.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.flashmall.common.entity.Shop;
import com.example.flashmall.common.util.CacheLockUtil;
import com.example.flashmall.common.util.RedisKeyUtil;
import com.example.flashmall.shop.mapper.ShopMapper;
import com.example.flashmall.shop.service.ShopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements ShopService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private CacheLockUtil cacheLockUtil;

    @Override
    public Shop getShopById(Long shopId) {
        String cacheKey = RedisKeyUtil.getShopKey(shopId);
        String lockKey = "lock:shop:" + shopId;
        return cacheLockUtil.executeWithLock(
                cacheKey,
                lockKey,
                () -> baseMapper.selectById(shopId),
                () -> RedisKeyUtil.SHOP_TTL_BASE + (long) (Math.random() * 600)
        );
    }

    @Override
    @Transactional
    public boolean updateShop(Shop shop) {
        // 1. 更新数据库
        boolean updated = updateById(shop);
        if (updated) {
            // 2. 删除缓存（先更新DB再删缓存）
            String key = RedisKeyUtil.getShopKey(shop.getId());
            redisTemplate.delete(key);
            log.info("缓存已删除: key={}", key);
        }
        return updated;
    }
}
