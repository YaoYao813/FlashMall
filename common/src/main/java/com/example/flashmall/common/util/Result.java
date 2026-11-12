package com.example.flashmall.common.util;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {
    private Integer code; // 响应码：200成功，500失败
    private String message; // 响应信息
    private T data; // 响应数据

    // 成功响应（带数据）
    public static <T> Result<T> success(T data) {
        return new Result<>(200, "操作成功", data);
    }

    // 成功响应（自定义消息 + 数据）
    public static <T> Result<T> success(T data, String message) {
        return new Result<>(200, message, data);
    }

    // 失败响应（带错误信息）
    public static <T> Result<T> fail(String message) {
        return new Result<>(500, message, null);
    }

    /**
     * 失败响应（自定义错误码）。
     *
     * <p>约定 500 = 业务失败（请求本身没问题，但不该这么干，比如重复支付）；
     * 202 = 暂时不可判定，可以原样重试（比如秒杀订单还在 MQ 里没落库）。
     * 202 单独存在是为了让客户端能区分「别重试了」和「等一会儿再试」。
     */
    public static <T> Result<T> fail(Integer code, String message) {
        return new Result<>(code, message, null);
    }
}