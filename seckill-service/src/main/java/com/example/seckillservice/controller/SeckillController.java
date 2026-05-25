package com.example.seckillservice.controller;

import com.example.flashmall.common.util.Result;
import com.example.seckillservice.service.SeckillService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/seckill")
public class SeckillController {
    @Autowired
    private SeckillService seckillService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> seckill(@RequestParam Long userId, @RequestParam Long productId) {
        Map<String, Object> result = new HashMap<>();
        try {
            Long orderId = seckillService.seckill(userId, productId);
            result.put("code", 200);
            result.put("message", "秒杀成功");
            result.put("data", orderId);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            result.put("code", 500);
            result.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
        }
    }
}
