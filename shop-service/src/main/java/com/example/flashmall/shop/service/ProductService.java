package com.example.flashmall.shop.service;

import com.example.flashmall.common.entity.Product;
import com.example.flashmall.common.util.PageResult;

public interface ProductService {
    Product getProductById(Long productId);
    boolean updateProduct(Product product);
    PageResult<Product> listProducts(int page, int size, String keyword, String sort);
}
