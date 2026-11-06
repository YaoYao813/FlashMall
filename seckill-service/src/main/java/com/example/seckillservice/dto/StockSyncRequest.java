package com.example.seckillservice.dto;

import lombok.Data;

/**
 * 库存预热 / 调整请求。
 * mode=reset 时 delta 忽略；mode=increment 时 delta 必填且不能为 0。
 */
@Data
public class StockSyncRequest {

    private String mode;
    private Integer delta;

    /** 调整原因，可选；写进审计 params_json，便于回溯为什么加减库存 */
    private String reason;
}
