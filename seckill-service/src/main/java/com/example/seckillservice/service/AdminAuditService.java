package com.example.seckillservice.service;

/**
 * Agent 写操作审计。实现必须是 best-effort：审计失败只记日志，不影响业务结果。
 */
public interface AdminAuditService {

    /**
     * 记录一次已执行成功的写操作。
     *
     * @param toolName       MCP 工具名，如 create_seckill_event
     * @param idempotencyKey 幂等键，可为空
     * @param operatorId     网关透传的操作人 ID，可为空（记 0）
     * @param params         请求参数，序列化为 JSON
     * @param result         执行结果，序列化为 JSON
     */
    void record(String toolName, String idempotencyKey, Long operatorId, Object params, Object result);
}
