package com.example.flashmall.order.controller;

import com.example.flashmall.common.entity.Order;
import com.example.flashmall.common.mapper.OrderMapper;
import com.example.flashmall.common.util.Result;
import com.example.flashmall.order.enums.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PayControllerTest {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Mock
    private OrderMapper orderMapper;

    @InjectMocks
    private PayController payController;

    @Test
    @DisplayName("支付成功：CAS 命中")
    void testPaySuccess() {
        when(orderMapper.selectById(1001L)).thenReturn(order(OrderStatus.PENDING_PAY));
        when(orderMapper.casPay(eq(1001L), any(LocalDateTime.class), anyString())).thenReturn(1);

        Result result = payController.mockPay(1001L, null);

        assertEquals(200, result.getCode());
    }

    @Test
    @DisplayName("重复支付：CAS 返回 0 且已是已支付态")
    void testPayTwice() {
        when(orderMapper.selectById(1001L)).thenReturn(order(OrderStatus.PENDING_PAY), order(OrderStatus.PAID));
        when(orderMapper.casPay(eq(1001L), any(LocalDateTime.class), anyString())).thenReturn(0);

        Result result = payController.mockPay(1001L, null);

        assertEquals(500, result.getCode());
        assertTrue(result.getMessage().contains("已支付"));
    }

    @Test
    @DisplayName("已被超时取消：明确告知已超时，不再写库")
    void testPayTimeoutOrder() {
        when(orderMapper.selectById(1001L)).thenReturn(order(OrderStatus.PENDING_PAY), order(OrderStatus.TIMEOUT));
        when(orderMapper.casPay(eq(1001L), any(LocalDateTime.class), anyString())).thenReturn(0);

        Result result = payController.mockPay(1001L, null);

        assertEquals(500, result.getCode());
        assertTrue(result.getMessage().contains("已超时"));
        verify(orderMapper, org.mockito.Mockito.never()).updateById(any());
    }

    @Test
    @DisplayName("订单还没落库，但订单号是刚生成的 → 202 可重试")
    void testFreshOrderNoReturns202() {
        when(orderMapper.selectOne(any())).thenReturn(null);

        Result result = payController.mockPay(null, LocalDateTime.now().format(TS) + "000123456");

        assertEquals(202, result.getCode());
        assertTrue(result.getMessage().contains("处理中"));
    }

    @Test
    @DisplayName("订单号格式不认识且查不到 → 500 订单不存在")
    void testUnknownOrderNo() {
        when(orderMapper.selectOne(any())).thenReturn(null);

        Result result = payController.mockPay(null, "ORD20261112000000abcdef12");

        assertEquals(500, result.getCode());
        assertTrue(result.getMessage().contains("订单不存在"));
    }

    @Test
    @DisplayName("订单号里的时间太老（消费者出了问题）→ 不再让用户重试")
    void testStaleOrderNoReturnsNotFound() {
        when(orderMapper.selectOne(any())).thenReturn(null);

        Result result = payController.mockPay(
                null, LocalDateTime.now().minusHours(2).format(TS) + "000123456");

        assertEquals(500, result.getCode());
        assertTrue(result.getMessage().contains("订单不存在"));
    }

    @Test
    @DisplayName("两个参数都没给")
    void testNoParam() {
        Result result = payController.mockPay(null, null);

        assertEquals(500, result.getCode());
        assertTrue(result.getMessage().contains("orderId 或 orderNo"));
    }

    @Test
    @DisplayName("回调：订单还没落库返回 fail，让平台重推")
    void testCallbackOrderMissing() {
        when(orderMapper.selectOne(any())).thenReturn(null);

        String resp = payController.payCallback(callback("SUCCESS"));

        assertEquals("fail", resp);
    }

    @Test
    @DisplayName("回调：订单已取消，仍返回 success（重推无意义）")
    void testCallbackAlreadyCancelled() {
        when(orderMapper.selectOne(any())).thenReturn(order(OrderStatus.CANCELLED));
        when(orderMapper.selectById(1001L)).thenReturn(order(OrderStatus.CANCELLED));
        when(orderMapper.casPay(eq(1001L), any(LocalDateTime.class), anyString())).thenReturn(0);

        String resp = payController.payCallback(callback("SUCCESS"));

        assertEquals("success", resp);
        verify(orderMapper, org.mockito.Mockito.never()).updateById(any());
    }

    @Test
    @DisplayName("回调：payStatus 不是 SUCCESS，直接 success 不处理")
    void testCallbackNotSuccess() {
        String resp = payController.payCallback(callback("FAIL"));

        assertEquals("success", resp);
        verify(orderMapper, org.mockito.Mockito.never()).selectOne(any());
    }

    private java.util.Map<String, String> callback(String payStatus) {
        java.util.Map<String, String> body = new java.util.HashMap<>();
        body.put("orderNo", "20261112200000123456789");
        body.put("tradeNo", "MOCK123");
        body.put("payStatus", payStatus);
        return body;
    }

    private Order order(OrderStatus status) {
        Order order = new Order();
        order.setId(1001L);
        order.setOrderNo("20261112200000123456789");
        order.setUserId(9001L);
        order.setProductId(7L);
        order.setStatus(status.getCode());
        return order;
    }
}
