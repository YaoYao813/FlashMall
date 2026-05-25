package com.example.flashmall.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.flashmall.common.entity.ProductStock;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProductStockMapper extends BaseMapper<ProductStock> {
}