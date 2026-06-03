package com.example.seckillservice.controller;

import com.example.flashmall.common.util.Result;
import com.example.seckillservice.config.SeckillRateLimiter;
import com.example.seckillservice.service.SeckillService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.constraints.NotNull;

@Validated
@RestController
@RequestMapping("/api/seckill")
public class SeckillController {

    @Autowired
    private SeckillService seckillService;
    @Autowired
    private SeckillRateLimiter rateLimiter;

    @PostMapping
    public Result<String> seckill(@RequestHeader("X-User-Id") @NotNull(message = "用户ID不能为空") Long userId,
                                  @RequestParam @NotNull(message = "商品ID不能为空") Long productId) {
        if (!rateLimiter.tryAcquire()) {
            return Result.fail("系统繁忙，请稍后重试");
        }
        try {
            String orderNo = seckillService.seckill(userId, productId);
            return Result.success(orderNo, "秒杀成功，订单处理中...");
        } finally {
            rateLimiter.release();
        }
    }
}
