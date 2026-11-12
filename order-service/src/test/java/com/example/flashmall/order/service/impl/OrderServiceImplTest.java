package com.example.flashmall.order.service.impl;

import com.example.flashmall.common.entity.Order;
import com.example.flashmall.common.entity.StockRollbackMessage;
import com.example.flashmall.common.mapper.OrderMapper;
import com.example.flashmall.order.enums.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private OrderServiceImpl orderService;

    @Test
    @DisplayName("取消成功：CAS 抢到状态，回滚消息带 orderId")
    void testCancelSuccess() {
        when(orderMapper.selectById(1001L)).thenReturn(order(OrderStatus.PENDING_PAY));
        when(orderMapper.casTerminate(1001L, OrderStatus.CANCELLED.getCode())).thenReturn(1);

        assertTrue(orderService.cancelOrder(1001L));

        ArgumentCaptor<StockRollbackMessage> captor = ArgumentCaptor.forClass(StockRollbackMessage.class);
        verify(rabbitTemplate).convertAndSend(eq("seckill.direct.exchange"), eq("seckill.stock.rollback"), captor.capture());
        assertEquals(1001L, captor.getValue().getOrderId());
        assertEquals("CANCEL", captor.getValue().getReason());
        // 不再用 updateById 整行覆盖
        verify(orderMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("连点取消：CAS 失败但已是取消态 → 补发回滚消息，仍返回成功")
    void testCancelIdempotentRetry() {
        when(orderMapper.selectById(1001L)).thenReturn(order(OrderStatus.PENDING_PAY), order(OrderStatus.CANCELLED));
        when(orderMapper.casTerminate(1001L, OrderStatus.CANCELLED.getCode())).thenReturn(0);

        assertTrue(orderService.cancelOrder(1001L));

        verify(rabbitTemplate).convertAndSend(eq("seckill.direct.exchange"), eq("seckill.stock.rollback"),
                any(StockRollbackMessage.class));
    }

    @Test
    @DisplayName("订单已经是取消态：直接补发回滚消息，不再 CAS")
    void testCancelAlreadyCancelled() {
        when(orderMapper.selectById(1001L)).thenReturn(order(OrderStatus.CANCELLED));

        assertTrue(orderService.cancelOrder(1001L));

        verify(orderMapper, never()).casTerminate(anyLong(), anyInt());
        verify(rabbitTemplate).convertAndSend(eq("seckill.direct.exchange"), eq("seckill.stock.rollback"),
                any(StockRollbackMessage.class));
    }

    @Test
    @DisplayName("已支付订单不能取消")
    void testCancelPaidOrder() {
        when(orderMapper.selectById(1001L)).thenReturn(order(OrderStatus.PAID));

        assertThrows(RuntimeException.class, () -> orderService.cancelOrder(1001L));
        verify(orderMapper, never()).casTerminate(anyLong(), anyInt());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(StockRollbackMessage.class));
    }

    @Test
    @DisplayName("并发下被别人改成已支付：CAS 返回 0 → 抛异常，不发回滚")
    void testCancelRacedWithPay() {
        when(orderMapper.selectById(1001L)).thenReturn(order(OrderStatus.PENDING_PAY), order(OrderStatus.PAID));
        when(orderMapper.casTerminate(1001L, OrderStatus.CANCELLED.getCode())).thenReturn(0);

        assertThrows(RuntimeException.class, () -> orderService.cancelOrder(1001L));
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(StockRollbackMessage.class));
    }

    @Test
    @DisplayName("MQ 不可用：状态已改成功，接口仍返回成功（库存偏差留给对账）")
    void testSendFailureDoesNotRollbackState() {
        when(orderMapper.selectById(1001L)).thenReturn(order(OrderStatus.PENDING_PAY));
        when(orderMapper.casTerminate(1001L, OrderStatus.CANCELLED.getCode())).thenReturn(1);
        doThrow(new RuntimeException("mq down"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(StockRollbackMessage.class));

        assertTrue(orderService.cancelOrder(1001L));
    }

    @Test
    @DisplayName("订单不存在")
    void testCancelMissingOrder() {
        when(orderMapper.selectById(1001L)).thenReturn(null);

        assertThrows(RuntimeException.class, () -> orderService.cancelOrder(1001L));
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
