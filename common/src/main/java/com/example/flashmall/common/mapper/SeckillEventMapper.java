package com.example.flashmall.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.flashmall.common.entity.SeckillEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 秒杀活动表 Mapper。
 * seckill_event 此前只有实体、没有 Mapper，管理接口是它的第一个读取方。
 */
@Mapper
public interface SeckillEventMapper extends BaseMapper<SeckillEvent> {

    /**
     * 查某商品当前未结束的活动（end_time > now），用于
     * 「同一商品不允许存在两场未结束活动」校验。
     *
     * @param excludeId 创建时传 null；修改时传自身 id，避免把自己判成冲突
     * @return 至多一条；没有则返回 null
     */
    @Select("<script>" +
            "SELECT * FROM seckill_event " +
            "WHERE product_id = #{productId} AND end_time &gt; #{now} " +
            "<if test='excludeId != null'> AND id &lt;&gt; #{excludeId} </if>" +
            "ORDER BY id ASC LIMIT 1" +
            "</script>")
    SeckillEvent selectUnfinishedByProductId(@Param("productId") Long productId,
                                             @Param("now") LocalDateTime now,
                                             @Param("excludeId") Long excludeId);

    /**
     * 原子增减活动库存，并用 SQL 条件兜住「调整后不能为负」。
     * 读-改-写会在并发调整时丢更新，因此这里直接 stock = stock + delta。
     *
     * @return 1 表示更新成功；0 表示活动不存在或调整后会为负
     */
    @Update("UPDATE seckill_event SET stock = stock + #{delta} " +
            "WHERE id = #{id} AND stock + #{delta} >= 0")
    int incrementStock(@Param("id") Long id, @Param("delta") int delta);
}
