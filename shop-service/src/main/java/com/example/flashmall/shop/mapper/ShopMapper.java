package com.example.flashmall.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.flashmall.common.entity.Shop;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ShopMapper extends BaseMapper<Shop>{
    @Select("SELECT id FROM shop")
    List<Long> selectAllIds();
}
