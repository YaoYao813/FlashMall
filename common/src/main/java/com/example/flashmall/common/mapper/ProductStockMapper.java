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

    /**
     * 订单取消/超时后把 1 件库存退回仓库。
     *
     * <p><b>故意不动 version</b>：乐观锁的 version 是给「批量扣减」之间互斥用的。
     * 如果回滚也 +1，正在跑的 decrementStock（它手里拿着旧 version）会全部失败，
     * 消息被打进死信——那是拿一个后台动作去打断主链路。回滚只 +stock，
     * 并发扣减读到的就是加回来的值，不会丢更新。
     */
    @Update("UPDATE product_stock SET stock = stock + 1 WHERE product_id = #{productId}")
    int rollbackStock(@Param("productId") Long productId);
}