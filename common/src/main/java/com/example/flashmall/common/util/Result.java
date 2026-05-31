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
}