package com.example.seckillservice.controller;

import com.example.seckillservice.config.SeckillRateLimiter;
import com.example.seckillservice.service.SeckillService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/seckill")
public class SeckillController {

    @Autowired
    private SeckillService seckillService;
    @Autowired
    private SeckillRateLimiter rateLimiter;

    @PostMapping
    public ResponseEntity<Map<String, Object>> seckill(@RequestHeader("X-User-Id") Long userId,
                                                        @RequestParam Long productId) {
        // ======== 限流检查 ========
        if (!rateLimiter.tryAcquire()) {
            // 不同意
            Map<String, Object> result = new HashMap<>();
            result.put("code", 429);
            result.put("message", "系统繁忙，请稍后重试");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(result);
        }
        try {
            Long resultCode = seckillService.seckill(userId, productId);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("message", "秒杀成功，订单处理中...");
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("code", 500);
            result.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
        } finally {
            // 无论成功还是失败，都释放许可
            rateLimiter.release();
        }
    }
}