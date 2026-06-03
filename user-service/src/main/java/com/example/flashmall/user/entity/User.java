package com.example.flashmall.user.entity;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;

@Data
public class User {
    private Long id;
    @NotBlank(message = "用户名不能为空")
    private String username;
    @NotBlank(message = "密码不能为空")
    private String password;   // 加密后
    private String phone;
    private String email;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
