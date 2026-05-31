package com.example.seckillservice;

import com.example.seckillservice.service.SeckillInitService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;

import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = {"com.example.flashmall", "com.example.seckillservice"})
@EnableDiscoveryClient
@MapperScan("com.example.flashmall.common.mapper")
public class SeckillServiceApplication implements CommandLineRunner {

    @Autowired
    private SeckillInitService seckillInitService;

    public static void main(String[] args) {
        SpringApplication.run(SeckillServiceApplication.class, args);
    }

    // 启动服务时 预热全部商品
    @Override
    public void run(String... args) throws Exception {
        seckillInitService.initAllStock();
    }
}
