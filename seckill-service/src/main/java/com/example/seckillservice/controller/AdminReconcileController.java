package com.example.seckillservice.controller;

import com.example.flashmall.common.util.Result;
import com.example.seckillservice.service.StockReconcileService;
import com.example.seckillservice.vo.ReconcileVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 库存对账接口。经网关 :8888 访问（路由 /api/admin/** → seckill-service）。
 *
 * <p>只读：不提供「一键修复」。偏差要先人工判断是「Redis 多扣」还是
 * 「订单还没落库」，自动修会把一次延迟变成一次真的超卖。
 */
@RestController
@RequestMapping("/api/admin/seckill/reconcile")
@RequiredArgsConstructor
public class AdminReconcileController {

    private final StockReconcileService stockReconcileService;

    @GetMapping
    public Result<ReconcileVO> reconcile(@RequestParam("eventId") Long eventId) {
        return Result.success(stockReconcileService.reconcile(eventId));
    }

    @GetMapping("/all")
    public Result<List<ReconcileVO>> all() {
        return Result.success(stockReconcileService.reconcileRecent());
    }
}
