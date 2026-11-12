package com.example.seckillservice.controller;

import com.example.flashmall.common.util.Result;
import com.example.seckillservice.service.DlqReplayService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 死信队列运维接口。经网关 :8888 访问（路由 /api/admin/** → seckill-service）。
 *
 * <p>只做「看积压 + 手动重放」，不做自动重放：死信往往意味着业务失败，
 * 自动重放容易把同一个错误放大成死循环。
 */
@RestController
@RequestMapping("/api/admin/seckill/dlq")
@RequiredArgsConstructor
public class AdminDlqController {

    private final DlqReplayService dlqReplayService;

    @GetMapping
    public Result<Map<String, Object>> depth() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("depth", dlqReplayService.depth());
        return Result.success(body);
    }

    @PostMapping("/replay")
    public Result<Map<String, Object>> replay(
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return Result.success(dlqReplayService.replay(limit));
    }
}
