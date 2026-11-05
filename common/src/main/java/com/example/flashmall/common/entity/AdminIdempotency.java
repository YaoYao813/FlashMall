package com.example.flashmall.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Agent 写操作幂等记录：保存首次请求的响应体，重复请求直接回放。
 */
@Data
@TableName("admin_idempotency")
public class AdminIdempotency implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 幂等键，格式 thread_id:tool_call_id，由 Python 侧生成 */
    @TableId(type = IdType.INPUT)
    private String idempotencyKey;

    /** 首次请求的 data 部分 JSON，用于回放 */
    private String responseJson;

    /** 首次请求时间，由数据库默认值填充 */
    private LocalDateTime createdAt;
}
