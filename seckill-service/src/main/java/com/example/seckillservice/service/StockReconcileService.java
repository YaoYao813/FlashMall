package com.example.seckillservice.service;

import com.example.seckillservice.vo.ReconcileVO;

import java.util.List;

/**
 * 秒杀库存对账。只读：发现偏差只报告，绝不自动改数据。
 *
 * <p>不自动修的原因：偏差有两种——「Redis 多扣了」和「订单还没落库」。
 * 自动补库存分不清这两者，会把一次延迟变成一次真的超卖。
 */
public interface StockReconcileService {

    /**
     * 对单个活动做一次对账。
     *
     * @throws RuntimeException 活动不存在
     */
    ReconcileVO reconcile(Long eventId);

    /**
     * 对「尚未结束或刚结束不久」的活动批量对账，供运营一次看全。
     */
    List<ReconcileVO> reconcileRecent();
}
