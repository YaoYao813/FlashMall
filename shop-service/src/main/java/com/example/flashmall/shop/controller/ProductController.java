package com.example.flashmall.shop.controller;

import com.example.flashmall.common.entity.Product;
import com.example.flashmall.shop.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/product")
public class ProductController {

    @Autowired
    private ProductService productService;

    @GetMapping("/{id}")
    public Product getProduct(@PathVariable Long id) {
        return productService.getProductById(id);
    }

    @PutMapping
    public String updateProduct(@RequestBody Product product) {
        boolean success = productService.updateProduct(product);
        return success ? "更新成功" : "更新失败";
    }
}
