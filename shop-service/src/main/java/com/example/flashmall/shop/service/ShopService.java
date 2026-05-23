package com.example.flashmall.shop.service;

import com.example.flashmall.common.entity.Shop;

public interface ShopService {
    Shop getShopById(Long shopId);
    boolean updateShop(Shop shop);
}
