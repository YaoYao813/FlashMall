package com.example.seckillservice.service;

public interface SeckillService {
    /**
     * 秒杀下单，返回订单ID，失败返回错误码
     */
    Long seckill(Long userId, Long productId);
}
