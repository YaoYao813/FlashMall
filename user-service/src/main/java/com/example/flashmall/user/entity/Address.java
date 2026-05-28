package com.example.flashmall.user.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Address {
    private Long id;
    private Long userId;
    private String receiver;
    private String phone;
    private String province;
    private String city;
    private String district;
    private String detail;
    private Integer isDefault;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}