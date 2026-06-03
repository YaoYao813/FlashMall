package com.example.flashmall.user.entity;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;

@Data
public class Address {
    private Long id;
    private Long userId;
    @NotBlank(message = "收货人不能为空")
    private String receiver;
    @NotBlank(message = "联系电话不能为空")
    private String phone;
    private String province;
    private String city;
    private String district;
    @NotBlank(message = "详细地址不能为空")
    private String detail;
    private Integer isDefault;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}