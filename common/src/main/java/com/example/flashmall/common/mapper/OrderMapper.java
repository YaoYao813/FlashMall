package com.example.flashmall.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.flashmall.common.entity.Order;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
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
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertBatch(@Param("list") List<Order> orders);

    /**
     * 消费幂等：批量消息重投时，先查出已经落库的订单号，避免重复扣库存。
     * 依赖 uk_order_no 唯一索引兜底极端并发。
     */
    @Select("<script>"
            + "SELECT order_no FROM `order` WHERE order_no IN "
            + "<foreach collection='list' item='item' open='(' separator=',' close=')'>#{item}</foreach>"
            + "</script>")
    List<String> selectExistingOrderNos(@Param("list") List<String> orderNos);

    /**
     * 支付状态 CAS：只有「待支付(0)」能变成「已支付(1)」。
     *
     * <p>为什么不能用 updateById：它是按主键整行更新，且不检查当前状态。
     * 支付线程和超时线程各自读到 status=0，后写的那个会覆盖先写的那个——
     * 用户付了钱，订单却变成「已超时」，库存也退回去了。
     *
     * @return 1=本次抢到状态；0=已被别人改过（已支付/已取消/已超时）
     */
    @Update("UPDATE `order` SET status = 1, pay_time = #{payTime}, trade_no = #{tradeNo} "
            + "WHERE id = #{id} AND status = 0")
    int casPay(@Param("id") Long id,
               @Param("payTime") LocalDateTime payTime,
               @Param("tradeNo") String tradeNo);

    /**
     * 终态 CAS：只有「待支付(0)」能变成取消(4)或超时(5)。
     *
     * @return 1=本次抢到终态；0=已经是终态或已支付
     */
    @Update("UPDATE `order` SET status = #{toStatus} WHERE id = #{id} AND status = 0")
    int casTerminate(@Param("id") Long id, @Param("toStatus") int toStatus);
}