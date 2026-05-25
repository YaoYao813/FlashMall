package com.example.flashmall.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;

@SpringBootApplication(scanBasePackages = {"com.example.flashmall.common", "com.example.flashmall.order"})
@MapperScan({"com.example.flashmall.order.mapper", "com.example.flashmall.common.mapper"})  // 扫描订单模块的 Mapper
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
