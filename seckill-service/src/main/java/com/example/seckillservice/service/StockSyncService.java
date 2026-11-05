package com.example.seckillservice.service;

import com.example.seckillservice.dto.StockSyncRequest;
import com.example.seckillservice.vo.StockSyncVO;

/**
 * 秒杀活动库存同步：未开始时预热，进行中增量调整。
 */
public interface StockSyncService {

    /**
     * @param eventId 活动 id
     * @param request mode=reset（忽略 delta）/ mode=increment（delta 必填且非 0）
     */
    StockSyncVO syncStock(Long eventId, StockSyncRequest request);
}
