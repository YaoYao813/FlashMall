package com.example.seckillservice.service;

import com.example.flashmall.common.entity.StockRollbackLog;
import com.example.flashmall.common.entity.StockRollbackMessage;
import com.example.flashmall.common.mapper.ProductStockMapper;
import com.example.flashmall.common.mapper.StockRollbackLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockRollbackServiceTest {

    @Mock
    private StockRollbackLogMapper stockRollbackLogMapper;

    @Mock
    private ProductStockMapper productStockMapper;

    @InjectMocks
    private StockRollbackService stockRollbackService;

    @Test
    @DisplayName("首次回滚：写台账 + 库存 +1")
    void testFirstRollback() {
        when(stockRollbackLogMapper.insert(any(StockRollbackLog.class))).thenReturn(1);
        when(productStockMapper.rollbackStock(7L)).thenReturn(1);

        boolean applied = stockRollbackService.rollbackOnce(message());

        assertTrue(applied);
        ArgumentCaptor<StockRollbackLog> captor = ArgumentCaptor.forClass(StockRollbackLog.class);
        verify(stockRollbackLogMapper).insert(captor.capture());
        assertEquals(1001L, captor.getValue().getOrderId());
        assertEquals("CANCEL", captor.getValue().getReason());
        verify(productStockMapper).rollbackStock(7L);
    }

    @Test
    @DisplayName("重复回滚：主键冲突 → 返回 false，且不再动库存")
    void testDuplicateRollback() {
        when(stockRollbackLogMapper.insert(any(StockRollbackLog.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry '1001' for key 'PRIMARY'"));

        boolean applied = stockRollbackService.rollbackOnce(message());

        assertFalse(applied);
        verify(productStockMapper, never()).rollbackStock(any());
    }

    @Test
    @DisplayName("商品行不存在：仍算已处理（台账已落），只是日志报错")
    void testMissingProductRow() {
        when(stockRollbackLogMapper.insert(any(StockRollbackLog.class))).thenReturn(1);
        when(productStockMapper.rollbackStock(7L)).thenReturn(0);

        assertTrue(stockRollbackService.rollbackOnce(message()));
        verify(productStockMapper).rollbackStock(7L);
    }

    private StockRollbackMessage message() {
        StockRollbackMessage msg = new StockRollbackMessage();
        msg.setOrderId(1001L);
        msg.setOrderNo("20261112200000123456789");
        msg.setProductId(7L);
        msg.setUserId(9001L);
        msg.setReason("CANCEL");
        return msg;
    }
}
