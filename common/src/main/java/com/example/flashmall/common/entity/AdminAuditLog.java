package com.example.flashmall.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Agent 写操作审计记录：谁、何时、什么参数、结果如何。
 *
 * <p>由 Java 在业务执行成功时写入，{@code approved} 恒为 1；
 * 用户在确认卡片上拒绝时不会产生业务请求，因此也不会有审计行。
 */
@Data
@TableName("admin_audit_log")
public class AdminAuditLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的幂等键，格式 thread_id:tool_call_id；无幂等的操作可为空 */
    private String idempotencyKey;

    /** 工具名，如 create_seckill_event */
    private String toolName;

    /** 操作人（网关透传的 X-User-Id）；缺失记 0 */
    private Long operatorId;

    /** 调用参数 JSON */
    private String paramsJson;

    /** 执行结果 JSON */
    private String resultJson;

    /** 用户是否批准：Java 只记录执行成功的写操作，因此为 1 */
    private Integer approved;

    /** 记录时间，由数据库默认值填充 */
    private LocalDateTime createdAt;
}
