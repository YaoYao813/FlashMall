package com.example.flashmall.order.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.flashmall.common.entity.Order;
import com.example.flashmall.common.mapper.OrderMapper;
import com.example.flashmall.common.util.Result;
import com.example.flashmall.order.enums.OrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 模拟支付。
 *
 * <p>状态变更一律走 CAS（{@code UPDATE ... WHERE status = 0}），不用 updateById：
 * 支付和「15 分钟超时取消」是两个线程，各自读到「待支付」后整行覆盖写入，
 * 会把对方的结果吞掉——用户付了钱、订单却显示已超时、库存还被退回去了。
 *
 * <p>错误码约定：{@code 500} 表示确定失败（别重试了），{@code 202} 表示暂时无法判定
 * （订单还在 MQ 里没落库，等一会儿原样重试）。
 */
@RestController
@RequestMapping("/api/pay")
@Slf4j
public class PayController {

    /**
     * 秒杀订单号前 14 位是下单时间（yyyyMMddHHmmss），见 SeckillServiceImpl#generateOrderNo。
     *
     * <p>只取到「秒」，不取前 17 位（yyyyMMddHHmmssSSS）：<b>JDK 8 的
     * DateTimeFormatter 解析「秒 + 3 位毫秒」这种紧邻的变长小数域会直接抛
     * DateTimeParseException</b>（同样的串在 JDK 17 上能解析，很容易写出「本地跑过、
     * 容器里失效」的代码）。判断十分钟的窗口，秒级精度足够。
     */
    private static final DateTimeFormatter ORDER_NO_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int ORDER_NO_TS_LEN = 14;
    /** 订单号里的时间在这个窗口内，才认为「订单可能还在异步落库」 */
    private static final long FRESH_WINDOW_MINUTES = 10;
    private static final int CODE_PROCESSING = 202;

    @Autowired
    private OrderMapper orderMapper;

    /**
     * 模拟支付（按 orderId 或 orderNo）
     */
    @PostMapping("/mock")
    public Result mockPay(@RequestParam(required = false) Long orderId,
                          @RequestParam(required = false) String orderNo) {
        Order order;
        if (orderNo != null) {
            order = orderMapper.selectOne(new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo));
        } else if (orderId != null) {
            order = orderMapper.selectById(orderId);
        } else {
            return Result.fail("请提供 orderId 或 orderNo");
        }

        if (order == null) {
            // 秒杀主链路是异步的：Lua 扣减 + 发 MQ 之后接口就返回了，订单还要等消费者落库。
            // 用户在这个窗口里点支付，查不到订单是正常的，不能报「订单不存在」吓人。
            if (orderNo != null && isFreshSeckillOrderNo(orderNo)) {
                return Result.fail(CODE_PROCESSING, "订单处理中，请稍后重试");
            }
            return Result.fail("订单不存在，请稍后重试（订单异步处理中）");
        }

        String tradeNo = "MOCK" + System.currentTimeMillis();
        int affected = orderMapper.casPay(order.getId(), LocalDateTime.now(), tradeNo);
        if (affected > 0) {
            return Result.success(null, "支付成功");
        }

        // CAS 失败说明状态已被别人改过，把真实原因告诉用户，而不是笼统的「状态不正确」
        Order latest = orderMapper.selectById(order.getId());
        int status = latest == null ? -1 : latest.getStatus();
        if (status == OrderStatus.PAID.getCode()
                || status == OrderStatus.SHIPPED.getCode()
                || status == OrderStatus.COMPLETED.getCode()) {
            return Result.fail("订单已支付，请勿重复支付");
        }
        if (status == OrderStatus.CANCELLED.getCode()) {
            return Result.fail("订单已取消，无法支付");
        }
        if (status == OrderStatus.TIMEOUT.getCode()) {
            return Result.fail("订单已超时，无法支付");
        }
        return Result.fail("订单状态不正确（status=" + status + "）");
    }

    /**
     * 模拟支付回调（微信/支付宝会异步回调这个接口）。
     *
     * <p>返回值语义是给支付平台看的：{@code success} = 已收到、别再推了；
     * {@code fail} = 没处理成，可以重推。所以只有「订单还没落库」才返回 fail，
     * 其余终态（已支付/已取消/已超时）一律 success——重推一万次也不会变。
     */
    @PostMapping("/callback")
    public String payCallback(@RequestBody Map<String, String> callbackData) {
        String orderNo = callbackData.get("orderNo");
        String tradeNo = callbackData.get("tradeNo");
        String payStatus = callbackData.get("payStatus");  // SUCCESS/FAIL
        if (!"SUCCESS".equals(payStatus)) {
            return "success";
        }
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo));
        if (order == null) {
            log.warn("支付回调：订单号不存在，让平台稍后重推：orderNo={}", orderNo);
            return "fail";
        }
        int affected = orderMapper.casPay(order.getId(), LocalDateTime.now(), tradeNo);
        if (affected == 0) {
            Order latest = orderMapper.selectById(order.getId());
            log.warn("支付回调：订单状态不允许支付，忽略：orderNo={}, status={}",
                    orderNo, latest == null ? "已删除" : latest.getStatus());
        }
        return "success";  // 返回 success 告诉支付平台已收到回调
    }

    /**
     * 判断订单号是否「刚下单、可能还在异步落库」。
     *
     * <p>只做启发式判断：订单号不是本系统格式（例如普通订单的 ORD 前缀）时返回 false，
     * 走「订单不存在」。十分钟之外的老订单号说明消费者出了问题，也不该让用户一直重试。
     */
    private boolean isFreshSeckillOrderNo(String orderNo) {
        if (orderNo.length() < ORDER_NO_TS_LEN) {
            return false;
        }
        try {
            LocalDateTime created = LocalDateTime.parse(orderNo.substring(0, ORDER_NO_TS_LEN), ORDER_NO_TS);
            LocalDateTime now = LocalDateTime.now();
            return !created.isBefore(now.minusMinutes(FRESH_WINDOW_MINUTES)) && !created.isAfter(now.plusMinutes(1));
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
