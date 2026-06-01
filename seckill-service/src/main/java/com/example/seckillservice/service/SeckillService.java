package com.example.seckillservice.service;

public interface SeckillService {
    /**
     * 秒杀下单，成功返回订单号，失败抛异常
     */
    String seckill(Long userId, Long productId);
}
