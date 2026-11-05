package com.example.seckillservice.controller;

import com.example.flashmall.common.util.PageResult;
import com.example.flashmall.common.util.Result;
import com.example.seckillservice.dto.CreateEventRequest;
import com.example.seckillservice.dto.StockSyncRequest;
import com.example.seckillservice.dto.UpdateEventRequest;
import com.example.seckillservice.service.AdminSeckillService;
import com.example.seckillservice.service.EventStatsService;
import com.example.seckillservice.service.StockSyncService;
import com.example.seckillservice.vo.EventStatsVO;
import com.example.seckillservice.vo.EventVO;
import com.example.seckillservice.vo.StockSyncVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 运营侧管理接口。经网关 :8888 访问，网关已完成 JWT 校验并注入 X-User-Id。
 */
@RestController
@RequestMapping("/api/admin/seckill/events")
@RequiredArgsConstructor
public class AdminSeckillController {

    private final AdminSeckillService adminSeckillService;
    private final StockSyncService stockSyncService;
    private final EventStatsService eventStatsService;

    @PostMapping
    public Result<EventVO> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-User-Id", required = false) Long operatorId,
            @RequestBody CreateEventRequest request) {
        return Result.success(adminSeckillService.createEvent(request, idempotencyKey, operatorId));
    }

    @GetMapping
    public Result<PageResult<EventVO>> list(
            @RequestParam(value = "status", defaultValue = "all") String status,
            @RequestParam(value = "productId", required = false) Long productId,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "10") Integer size) {
        return Result.success(adminSeckillService.listEvents(status, productId, page, size));
    }

    @PutMapping("/{id}")
    public Result<EventVO> update(@PathVariable("id") Long id,
                                  @RequestBody UpdateEventRequest request) {
        return Result.success(adminSeckillService.updateEvent(id, request));
    }

    @PostMapping("/{id}/stock-sync")
    public Result<StockSyncVO> stockSync(@PathVariable("id") Long id,
                                         @RequestBody StockSyncRequest request) {
        return Result.success(stockSyncService.syncStock(id, request));
    }

    @GetMapping("/{id}/stats")
    public Result<EventStatsVO> stats(@PathVariable("id") Long id) {
        return Result.success(eventStatsService.stats(id));
    }
}
