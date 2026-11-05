package com.example.seckillservice.service;

import com.example.flashmall.common.util.PageResult;
import com.example.seckillservice.dto.CreateEventRequest;
import com.example.seckillservice.dto.UpdateEventRequest;
import com.example.seckillservice.vo.EventVO;

public interface AdminSeckillService {

    /**
     * 创建秒杀活动。同一 Idempotency-Key 重复调用直接回放首次响应，不重复建活动。
     *
     * @param operatorId 网关透传的操作人 ID，M1 仅记录日志，审计在 M4 接入
     */
    EventVO createEvent(CreateEventRequest request, String idempotencyKey, Long operatorId);

    /**
     * 分页查询活动列表。status 由起止时间推导，不接受数据库状态字段。
     *
     * @param status    all / pending / running / ended，空或 all 表示不过滤
     * @param productId 可选，按商品过滤
     */
    PageResult<EventVO> listEvents(String status, Long productId, Integer page, Integer size);

    /**
     * 修改或下线活动。
     * 未开始：可改价格 / 库存 / 起止时间；任何未结束的活动都可以只把 endTime 改到当前或过去来下线；
     * 进行中只允许改 endTime；已结束不可再改。
     */
    EventVO updateEvent(Long id, UpdateEventRequest request);
}
