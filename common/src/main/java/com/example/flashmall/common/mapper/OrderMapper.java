package com.example.flashmall.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.flashmall.common.entity.Order;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    @Insert("<script>"
            + "INSERT INTO `order` (order_no, user_id, product_id, product_name, price, quantity, total_amount, status) VALUES "
            + "<foreach collection='list' item='item' separator=','>"
            + "(#{item.orderNo}, #{item.userId}, #{item.productId}, #{item.productName}, "
            + "#{item.price}, #{item.quantity}, #{item.totalAmount}, #{item.status})"
            + "</foreach>"
            + "</script>")
    int insertBatch(@Param("list") List<Order> orders);
}