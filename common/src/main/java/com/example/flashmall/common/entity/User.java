package com.example.flashmall.common.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@Data
@TableName("user")
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    // 主键
    @TableId(type = IdType.AUTO)
    private Long id;

    // 用户名（对应表格，唯一约束）
    private String username;

    // 加密后的密码
    private String password;

    // 手机号（表格新增字段）
    private String phone;

    // 用户角色：0=买家/1=商家（表格新增字段）
    private Integer role;

    // 创建时间（表格新增字段，驼峰命名对应数据库下划线`create_time`）
    private LocalDateTime createTime;
}