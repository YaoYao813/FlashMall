package com.example.flashmall.shop;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = {"com.example.flashmall.shop", "com.example.flashmall.common"})
@EnableDiscoveryClient
@MapperScan({"com.example.flashmall.shop.mapper", "com.example.flashmall.common.mapper"})
public class ShopServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShopServiceApplication.class, args);
    }
}
