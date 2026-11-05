package com.example.seckillservice.service;

import com.example.seckillservice.vo.EventStatsVO;

/**
 * 活动统计快照。只读，不参与秒杀主链路。
 */
public interface EventStatsService {

    EventStatsVO stats(Long eventId);
}
