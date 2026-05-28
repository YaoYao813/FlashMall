package com.example.flashmall.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.flashmall.common.entity.ProductStock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProductStockMapper extends BaseMapper<ProductStock> {

    @Update("UPDATE product_stock SET stock = stock - #{count}, version = version + 1 "
            + "WHERE product_id = #{productId} AND version = #{version} AND stock >= #{count}")
    int decrementStock(@Param("productId") Long productId,
                       @Param("count") int count,
                       @Param("version") Integer version);
}