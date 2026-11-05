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
}
