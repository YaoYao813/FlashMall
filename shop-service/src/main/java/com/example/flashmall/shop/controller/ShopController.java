package com.example.flashmall.shop.controller;

import com.example.flashmall.common.entity.Shop;
import com.example.flashmall.shop.service.ShopService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.example.flashmall.common.util.Result;

@RestController
@RequestMapping("/api/shop")
public class ShopController {

    @Autowired
    private ShopService shopService;

    @GetMapping("/{id}")
    public Result getShop(@PathVariable Long id) {
        Shop shop = shopService.getShopById(id);
        return Result.success(shop);
    }

    @PutMapping
    public Result updateShop(@RequestBody Shop shop) {
        boolean ok = shopService.updateShop(shop);
        return ok ? Result.success() : Result.fail("更新失败");
    }
}
