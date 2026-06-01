package com.example.flashmall.order.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.flashmall.common.entity.Order;
import com.example.flashmall.common.mapper.OrderMapper;
import com.example.flashmall.common.util.Result;
import com.example.flashmall.order.enums.OrderStatus;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/pay")
public class PayController {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 模拟支付（按 orderId）
     */
    @PostMapping("/mock")
    public Result mockPay(@RequestParam(required = false) Long orderId,
                          @RequestParam(required = false) String orderNo) {
        Order order;
        if (orderNo != null) {
            LambdaQueryWrapper<Order> query = new LambdaQueryWrapper<Order>()
                    .eq(Order::getOrderNo, orderNo);
            order = orderMapper.selectOne(query);
        } else if (orderId != null) {
            order = orderMapper.selectById(orderId);
        } else {
            return Result.fail("请提供 orderId 或 orderNo");
        }
        if (order == null) {
            return Result.fail("订单不存在，请稍后重试（订单异步处理中）");
        }
        if (order.getStatus() != OrderStatus.PENDING_PAY.getCode()) {
            return Result.fail("订单状态不正确");
        }
        String tradeNo = "MOCK" + System.currentTimeMillis();
        order.setStatus(OrderStatus.PAID.getCode());
        order.setPayTime(LocalDateTime.now());
        order.setTradeNo(tradeNo);
        orderMapper.updateById(order);
        return Result.success(null, "支付成功");
    }

    /**
     * 模拟支付回调（微信/支付宝会异步回调这个接口）
     */
    @PostMapping("/callback")
    public String payCallback(@RequestBody Map<String, String> callbackData) {
        String orderNo = callbackData.get("orderNo");
        String tradeNo = callbackData.get("tradeNo");
        String payStatus = callbackData.get("payStatus");  // SUCCESS/FAIL
        if (!"SUCCESS".equals(payStatus)) {
            return "fail";
        }
        // 查询订单并更新
        LambdaQueryWrapper<Order> query = new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo);
        Order order = orderMapper.selectOne(query);
        if (order != null && order.getStatus() == OrderStatus.PENDING_PAY.getCode()) {
            order.setStatus(OrderStatus.PAID.getCode());
            order.setPayTime(LocalDateTime.now());
            order.setTradeNo(tradeNo);
            orderMapper.updateById(order);
        }
        return "success";  // 返回 success 告诉支付平台已收到回调
    }
}
