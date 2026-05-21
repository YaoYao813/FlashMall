package com.example.flashmall.shop.common;

import java.io.Serializable;

/**
 * 统一 API 响应封装类
 */
public class ApiResponse<T> implements Serializable {

	private int code;
	private String message;
	private T data;

	public ApiResponse() {}

	public ApiResponse(int code, String message, T data) {
		this.code = code;
		this.message = message;
		this.data = data;
	}

	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<>(0, "success", data);
	}

	public static <T> ApiResponse<T> ok() {
		return ok(null);
	}

	public static <T> ApiResponse<T> fail(String message) {
		return new ApiResponse<>(-1, message, null);
	}

	public int getCode() {
		return code;
	}

	public void setCode(int code) {
		this.code = code;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public T getData() {
		return data;
	}

	public void setData(T data) {
		this.data = data;
	}
}


