package com.example.flashmall.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.flashmall.common.entity.AdminIdempotency;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AdminIdempotencyMapper extends BaseMapper<AdminIdempotency> {
}
