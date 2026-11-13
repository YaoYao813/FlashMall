package com.example.flashmall.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.flashmall.common.entity.StockRollbackLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 库存回滚台账。只用 BaseMapper 的 insert / selectById：
 * 幂等性靠主键冲突，不需要额外查询。
 */
@Mapper
public interface StockRollbackLogMapper extends BaseMapper<StockRollbackLog> {
}
