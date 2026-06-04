package com.example.flashmall.order.controller;

import com.example.flashmall.common.entity.Order;
import com.example.flashmall.common.mapper.OrderMapper;
import com.example.flashmall.common.util.PageResult;
import com.example.flashmall.common.util.Result;
import com.example.flashmall.order.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderMapper orderMapper;

    @GetMapping
    public Result listOrders(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        PageResult<Order> pageResult = orderService.listOrders(userId, page, size);
        return Result.success(pageResult);
    }

    @GetMapping("/{orderNo}")
    public Result getOrderByOrderNo(@RequestHeader("X-User-Id") Long currentUserId,
                                    @PathVariable String orderNo) {
        Order order = orderService.getOrderByOrderNo(orderNo);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (!order.getUserId().equals(currentUserId)) {
            return Result.fail("无权查看该订单");
        }
        return Result.success(order);
    }

    @PostMapping("/{orderId}/cancel")
    public Result cancelOrder(@RequestHeader("X-User-Id") Long currentUserId,
                              @PathVariable Long orderId) {
        try {
            Order order = orderMapper.selectById(orderId);
            if (order == null) {
                return Result.fail("订单不存在");
            }
            if (!order.getUserId().equals(currentUserId)) {
                return Result.fail("无权取消该订单");
            }
            boolean success = orderService.cancelOrder(orderId);
            return success ? Result.success("订单已取消") : Result.fail("取消订单失败");
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }
}
