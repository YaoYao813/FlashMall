package com.example.flashmall.shop.controller;

import com.example.flashmall.common.entity.Shop;
import com.example.flashmall.shop.service.ShopService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import com.example.flashmall.common.util.Result;
import com.example.flashmall.common.util.BloomFilterUtil;
import com.example.flashmall.common.util.CacheLockUtil;

import java.util.Random;

@RestController
@RequestMapping("/api/shop")
@RequiredArgsConstructor
public class ShopController {

    private final ShopService shopService;
    private final BloomFilterUtil bloomFilterUtil;
    private final CacheLockUtil cacheLockUtil;

    @GetMapping("/{id}")
    public Result getShop(@PathVariable Long id) {
        // 第一层：布隆过滤器拦截
        if (!bloomFilterUtil.mightContainShopId(id)) {
            return Result.success(null);
        }

        // 第二层：缓存+分布式锁
        Shop shop = cacheLockUtil.executeWithLock(
                "cache:shop:" + id,
                "lock:shop:" + id,
                () -> shopService.getShopById(id),
                () -> 3600L + new Random().nextInt(600)
        );

        return Result.success(shop);
    }

    @PutMapping
    public Result updateShop(@RequestBody Shop shop) {
        boolean ok = shopService.updateShop(shop);
        return ok ? Result.success(null, "更新成功") : Result.fail("更新失败");
    }
}
