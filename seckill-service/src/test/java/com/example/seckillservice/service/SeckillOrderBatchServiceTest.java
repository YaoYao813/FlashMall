package com.example.seckillservice.service;

import com.example.flashmall.common.entity.Product;
import com.example.flashmall.common.entity.ProductStock;
import com.example.flashmall.common.entity.SeckillMessage;
import com.example.flashmall.common.mapper.OrderMapper;
import com.example.flashmall.common.mapper.ProductMapper;
import com.example.flashmall.common.mapper.ProductStockMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillOrderBatchServiceTest {

    @Mock
    private ProductStockMapper productStockMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private ProductMapper productMapper;
    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private SeckillOrderBatchService batchService;

    @Test
    @DisplayName("整批消息都已落库：跳过，不扣库存")
    void testAllDuplicated() {
        List<SeckillMessage> batch = Arrays.asList(
                new SeckillMessage(1L, 7L, "NO-1"),
                new SeckillMessage(2L, 7L, "NO-2"));
        when(orderMapper.selectExistingOrderNos(any())).thenReturn(Arrays.asList("NO-1", "NO-2"));

        int processed = batchService.processBatchOrders(7L, batch);

        assertEquals(0, processed);
        verify(productStockMapper, never()).decrementStock(anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("部分重复：只按新消息数量扣库存")
    void testPartiallyDuplicated() {
        List<SeckillMessage> batch = Arrays.asList(
                new SeckillMessage(1L, 7L, "NO-1"),
                new SeckillMessage(2L, 7L, "NO-2"),
                new SeckillMessage(3L, 7L, "NO-3"));
        when(orderMapper.selectExistingOrderNos(any())).thenReturn(Collections.singletonList("NO-1"));
        stubStockAndProduct();

        int processed = batchService.processBatchOrders(7L, batch);

        assertEquals(2, processed);
        verify(productStockMapper).decrementStock(eq(7L), eq(2), eq(0));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.example.flashmall.common.entity.Order>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(orderMapper).insertBatch(captor.capture());
        assertEquals(2, captor.getValue().size());
    }

    @Test
    @DisplayName("乐观锁冲突：抛异常，让调用方回补 Redis")
    void testOptimisticLockConflict() {
        List<SeckillMessage> batch = Collections.singletonList(new SeckillMessage(1L, 7L, "NO-1"));
        when(orderMapper.selectExistingOrderNos(any())).thenReturn(Collections.emptyList());
        when(productStockMapper.selectOne(any())).thenReturn(stock(10, 0));
        when(productStockMapper.decrementStock(anyLong(), anyInt(), any())).thenReturn(0);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> batchService.processBatchOrders(7L, batch));

        assertEquals("乐观锁冲突，productId=7", ex.getMessage());
        verify(orderMapper, never()).insertBatch(any());
    }

    @Test
    @DisplayName("仓库库存不足：抛异常，不写订单")
    void testNotEnoughStock() {
        List<SeckillMessage> batch = Collections.singletonList(new SeckillMessage(1L, 7L, "NO-1"));
        when(orderMapper.selectExistingOrderNos(any())).thenReturn(Collections.emptyList());
        when(productStockMapper.selectOne(any())).thenReturn(stock(0, 0));

        assertThrows(RuntimeException.class, () -> batchService.processBatchOrders(7L, batch));
        verify(orderMapper, never()).insertBatch(any());
    }

    private void stubStockAndProduct() {
        when(productStockMapper.selectOne(any())).thenReturn(stock(10, 0));
        when(productStockMapper.decrementStock(anyLong(), anyInt(), any())).thenReturn(1);
        Product product = new Product();
        product.setTitle("秒杀商品");
        product.setPrice(new BigDecimal("99.00"));
        when(productMapper.selectById(anyLong())).thenReturn(product);
    }

    private ProductStock stock(int stock, int version) {
        ProductStock ps = new ProductStock();
        ps.setProductId(7L);
        ps.setStock(stock);
        ps.setVersion(version);
        return ps;
    }
}
