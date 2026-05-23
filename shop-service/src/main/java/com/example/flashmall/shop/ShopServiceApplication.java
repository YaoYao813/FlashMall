package com.example.flashmall.shop;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.example.flashmall.shop", "com.example.flashmall.common"})
@MapperScan("com.example.flashmall.shop.mapper")
public class ShopServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShopServiceApplication.class, args);
    }
}
