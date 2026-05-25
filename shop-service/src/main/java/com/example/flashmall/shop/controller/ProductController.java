package com.example.flashmall.shop.controller;

import com.example.flashmall.common.entity.Product;
import com.example.flashmall.common.entity.Shop;
import com.example.flashmall.shop.service.ProductService;
import com.example.flashmall.common.util.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import com.example.flashmall.common.util.BloomFilterUtil;
import com.example.flashmall.common.util.CacheLockUtil;
import java.util.Random;

@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final BloomFilterUtil bloomFilterUtil;
    private final CacheLockUtil cacheLockUtil;

    @GetMapping("/{id}")
    public Result getProduct(@PathVariable Long id) {// 第一层：布隆过滤器拦截
        if (!bloomFilterUtil.mightContainProductId(id)) {
            return Result.success(null);
        }

        // 第二层：缓存+分布式锁
        Product product = cacheLockUtil.executeWithLock(
                "cache:product:" + id,
                "lock:product:" + id,
                () -> productService.getProductById(id),
                () -> 3600L + new Random().nextInt(600)
        );

        return Result.success(product);
    }

    @PutMapping
    public String updateProduct(@RequestBody Product product) {
        boolean success = productService.updateProduct(product);
        return success ? "更新成功" : "更新失败";
    }
}
