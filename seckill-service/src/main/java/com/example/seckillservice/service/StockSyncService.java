package com.example.seckillservice.service;

import com.example.seckillservice.dto.StockSyncRequest;
import com.example.seckillservice.vo.StockSyncVO;

/**
 * 秒杀活动库存同步：未开始时预热，进行中增量调整。
 */
public interface StockSyncService {

    /**
     * @param eventId 活动 id
     * @param request mode=reset（忽略 delta）/ mode=increment（delta 必填且非 0）
     */
    StockSyncVO syncStock(Long eventId, StockSyncRequest request);

    /**
     * 带幂等与审计的库存同步。
     *
     * <p>只有 {@code mode=increment} 认 {@code Idempotency-Key}：同键重复调用直接回放首次结果，
     * 不会再次加减库存。{@code reset} 本身是 SET 语义，忽略该键。
     *
     * @param idempotencyKey 由 Agent 生成，格式 {@code thread_id:tool_call_id}；为空时按旧行为执行
     * @param operatorId     网关透传的操作人 ID，写审计用；可为空
     * @param toolName       MCP 工具名，写审计用；为空时默认 adjust_seckill_stock
     */
    StockSyncVO syncStock(Long eventId, StockSyncRequest request,
                          String idempotencyKey, Long operatorId, String toolName);
}
