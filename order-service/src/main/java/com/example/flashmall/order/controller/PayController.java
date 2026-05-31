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
     * 模拟支付：用户点击"去支付"
     */
    @PostMapping("/mock")
    public Result mockPay(@RequestParam Long orderId) {
        // 1. 检查订单状态
        Order order = orderMapper.selectById(orderId);
        if (order == null || order.getStatus() != OrderStatus.PENDING_PAY.getCode()) {
            // 订单不存在/不是“未支付”
            return Result.fail("订单状态不正确");
        }
        // 2. 模拟调用支付网关（这里直接成功）
        String tradeNo = "MOCK" + System.currentTimeMillis();
        // 3. 更新订单状态
        order.setStatus(OrderStatus.PAID.getCode());// 改为已支付
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
