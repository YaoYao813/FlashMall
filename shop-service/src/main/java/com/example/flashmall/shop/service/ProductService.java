package com.example.flashmall.shop.service;

import com.example.flashmall.common.entity.Product;

public interface ProductService {
    Product getProductById(Long productId);
    boolean updateProduct(Product product);
}
