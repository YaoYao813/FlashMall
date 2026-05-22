package com.example.flashmall.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.flashmall.common.entity.Product;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {
    // 使用 MyBatis-Plus 内置方法，无需写 SQL
}
