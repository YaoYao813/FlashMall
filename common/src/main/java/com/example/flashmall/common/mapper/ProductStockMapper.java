package com.example.flashmall.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.flashmall.common.entity.ProductStock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProductStockMapper extends BaseMapper<ProductStock> {

    /**
     * 按商品查仓库库存行。预热前用它校验活动配额不超过仓库实际剩余。
     */
    @Select("SELECT * FROM product_stock WHERE product_id = #{productId} LIMIT 1")
    ProductStock selectByProductId(@Param("productId") Long productId);

    @Update("UPDATE product_stock SET stock = stock - #{count}, version = version + 1 "
            + "WHERE product_id = #{productId} AND version = #{version} AND stock >= #{count}")
    int decrementStock(@Param("productId") Long productId,
                       @Param("count") int count,
                       @Param("version") Integer version);
}