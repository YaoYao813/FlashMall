package com.example.seckillservice.service.impl;

import com.example.flashmall.common.entity.AdminAuditLog;
import com.example.flashmall.common.mapper.AdminAuditLogMapper;
import com.example.seckillservice.service.AdminAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuditServiceImpl implements AdminAuditService {

    private static final long UNKNOWN_OPERATOR = 0L;

    private final AdminAuditLogMapper adminAuditLogMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void record(String toolName, String idempotencyKey, Long operatorId,
                       Object params, Object result) {
        try {
            AdminAuditLog row = new AdminAuditLog();
            row.setToolName(toolName);
            row.setIdempotencyKey(idempotencyKey);
            row.setOperatorId(operatorId == null ? UNKNOWN_OPERATOR : operatorId);
            row.setParamsJson(toJson(params));
            row.setResultJson(toJson(result));
            row.setApproved(1);
            adminAuditLogMapper.insert(row);
        } catch (Exception e) {
            // 审计是旁路：写失败不能回滚已生效的业务
            log.error("写审计日志失败（业务已生效）：tool={}, key={}, operatorId={}",
                    toolName, idempotencyKey, operatorId, e);
        }
    }

    private String toJson(Object value) throws Exception {
        return value == null ? null : objectMapper.writeValueAsString(value);
    }
}
