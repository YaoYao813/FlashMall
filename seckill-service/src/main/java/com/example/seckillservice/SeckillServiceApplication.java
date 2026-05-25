package com.example.seckillservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;

@SpringBootApplication(scanBasePackages = {"com.example.flashmall", "com.example.seckillservice"})
@MapperScan("com.example.flashmall.common.mapper")
public class SeckillServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SeckillServiceApplication.class, args);
    }
}
