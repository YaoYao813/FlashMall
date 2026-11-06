package com.example.seckillservice.service.impl;

import com.example.flashmall.common.entity.AdminAuditLog;
import com.example.flashmall.common.mapper.AdminAuditLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAuditServiceImplTest {

    private AdminAuditLogMapper mapper;
    private AdminAuditServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(AdminAuditLogMapper.class);
        service = new AdminAuditServiceImpl(mapper, new ObjectMapper());
    }

    @Test
    void writesApprovedRowWithSerializedParamsAndResult() {
        when(mapper.insert(any(AdminAuditLog.class))).thenReturn(1);

        service.record("adjust_seckill_stock", "t:1", 7L,
                Collections.singletonMap("delta", 10), Collections.singletonMap("dbStock", 60));

        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(mapper).insert(captor.capture());
        AdminAuditLog row = captor.getValue();
        assertEquals("adjust_seckill_stock", row.getToolName());
        assertEquals("t:1", row.getIdempotencyKey());
        assertEquals(7L, row.getOperatorId());
        assertEquals(1, row.getApproved());
        assertTrue(row.getParamsJson().contains("\"delta\":10"));
        assertTrue(row.getResultJson().contains("\"dbStock\":60"));
    }

    @Test
    void fallsBackToZeroWhenOperatorMissing() {
        when(mapper.insert(any(AdminAuditLog.class))).thenReturn(1);

        service.record("create_seckill_event", "t:2", null, null, null);

        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(mapper).insert(captor.capture());
        assertEquals(0L, captor.getValue().getOperatorId());
    }

    @Test
    void insertFailureDoesNotBreakBusiness() {
        when(mapper.insert(any(AdminAuditLog.class))).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> service.record("create_seckill_event", "t:3", 1L, "p", "r"));
    }
}
