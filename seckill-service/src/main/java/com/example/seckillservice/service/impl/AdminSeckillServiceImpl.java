package com.example.seckillservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.flashmall.common.entity.AdminIdempotency;
import com.example.flashmall.common.entity.Product;
import com.example.flashmall.common.entity.ProductStock;
import com.example.flashmall.common.entity.SeckillEvent;
import com.example.flashmall.common.mapper.AdminIdempotencyMapper;
import com.example.flashmall.common.mapper.ProductMapper;
import com.example.flashmall.common.mapper.ProductStockMapper;
import com.example.flashmall.common.mapper.SeckillEventMapper;
import com.example.flashmall.common.util.PageResult;
import com.example.seckillservice.cache.SeckillEventCache;
import com.example.seckillservice.dto.CreateEventRequest;
import com.example.seckillservice.dto.UpdateEventRequest;
import com.example.seckillservice.enums.EventStatus;
import com.example.seckillservice.service.AdminAuditService;
import com.example.seckillservice.service.AdminSeckillService;
import com.example.seckillservice.vo.EventVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理侧秒杀活动服务。
 *
 * <p>幂等方案：业务插入与幂等记录写在同一个本地事务里。并发同 key 时，
 * 后到的请求在插入 admin_idempotency 时撞主键（InnoDB 会等先到的事务提交），
 * 事务整体回滚，再在事务外读取已提交的首次响应进行回放。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSeckillServiceImpl implements AdminSeckillService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 20;

    private final SeckillEventMapper seckillEventMapper;
    private final ProductMapper productMapper;
    private final ProductStockMapper productStockMapper;
    private final AdminIdempotencyMapper adminIdempotencyMapper;
    private final TransactionOperations transactionOperations;
    private final ObjectMapper objectMapper;
    private final SeckillEventCache seckillEventCache;
    private final AdminAuditService adminAuditService;

    // ==================== 接口 1：创建 ====================

    @Override
    public EventVO createEvent(CreateEventRequest request, String idempotencyKey, Long operatorId) {
        if (request == null) {
            throw new RuntimeException("请求参数不能为空");
        }
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw new RuntimeException("Idempotency-Key 不能为空");
        }
        String key = idempotencyKey.trim();

        // 快路径：已处理过就直接回放，不进入事务
        EventVO existing = replay(key);
        if (existing != null) {
            return existing;
        }

        log.info("创建秒杀活动：operatorId={}, productId={}, key={}",
                operatorId, request.getProductId(), key);
        try {
            EventVO created = transactionOperations.execute(status -> doCreate(request, key));
            // 审计放在事务提交之后：审计写失败不能把已建好的活动回滚掉
            adminAuditService.record("create_seckill_event", key, operatorId, request, created);
            return created;
        } catch (DuplicateKeyException e) {
            // 并发同 key：先到的请求已提交，回放它的响应
            EventVO concurrent = replay(key);
            if (concurrent != null) {
                return concurrent;
            }
            throw new RuntimeException("请求处理中，请稍后重试");
        } catch (DataAccessException e) {
            log.error("创建秒杀活动数据库异常：key={}", key, e);
            throw new RuntimeException("创建活动失败，请稍后重试");
        }
    }

    private EventVO doCreate(CreateEventRequest request, String key) {
        LocalDateTime now = LocalDateTime.now();
        validateCreate(request, now);

        SeckillEvent event = new SeckillEvent();
        event.setProductId(request.getProductId());
        event.setSeckillPrice(request.getSeckillPrice());
        event.setStock(request.getStock());
        event.setStartTime(request.getStartTime());
        event.setEndTime(request.getEndTime());
        if (seckillEventMapper.insert(event) != 1) {
            throw new RuntimeException("创建活动失败，请稍后重试");
        }

        EventVO vo = toVO(event, now, null);
        vo.setReplayed(false);

        AdminIdempotency record = new AdminIdempotency();
        record.setIdempotencyKey(key);
        record.setResponseJson(toJson(vo));
        adminIdempotencyMapper.insert(record);
        return vo;
    }

    /**
     * 校验顺序与契约 §1.1 一致；任一失败抛 RuntimeException，
     * 由 GlobalExceptionHandler 转成 HTTP 200 + code=500。
     */
    private void validateCreate(CreateEventRequest request, LocalDateTime now) {
        if (request.getProductId() == null) {
            throw new RuntimeException("商品ID不能为空");
        }
        Product product = productMapper.selectById(request.getProductId());
        if (product == null) {
            throw new RuntimeException("商品不存在");
        }

        if (request.getSeckillPrice() == null) {
            throw new RuntimeException("秒杀价不能为空");
        }
        if (request.getSeckillPrice().compareTo(product.getPrice()) >= 0) {
            throw new RuntimeException("秒杀价必须低于商品原价");
        }

        int availableStock = availableStockOf(product.getId());
        if (request.getStock() == null || request.getStock() <= 0
                || request.getStock() > availableStock) {
            throw new RuntimeException("秒杀库存必须在 1 到商品库存之间");
        }

        if (request.getStartTime() == null || request.getEndTime() == null) {
            throw new RuntimeException("活动时间不能为空");
        }
        if (!request.getStartTime().isBefore(request.getEndTime())) {
            throw new RuntimeException("开始时间必须早于结束时间");
        }
        if (!request.getEndTime().isAfter(now)) {
            throw new RuntimeException("结束时间必须晚于当前时间");
        }

        SeckillEvent conflict = seckillEventMapper.selectUnfinishedByProductId(
                request.getProductId(), now, null);
        if (conflict != null) {
            throw new RuntimeException("该商品已存在未结束的活动（id=" + conflict.getId() + "）");
        }
    }

    // ==================== 接口 2：列表 ====================

    @Override
    public PageResult<EventVO> listEvents(String status, Long productId, Integer page, Integer size) {
        int pageNo = page == null || page < 1 ? 1 : page;
        int pageSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        EventStatus statusFilter = parseStatusFilter(status);
        LocalDateTime now = LocalDateTime.now();

        LambdaQueryWrapper<SeckillEvent> wrapper = new LambdaQueryWrapper<>();
        if (productId != null) {
            wrapper.eq(SeckillEvent::getProductId, productId);
        }
        // 状态没有库字段，翻译成时间区间条件；边界与 EventStatus.of 保持一致
        if (statusFilter == EventStatus.PENDING) {
            wrapper.gt(SeckillEvent::getStartTime, now);
        } else if (statusFilter == EventStatus.RUNNING) {
            wrapper.le(SeckillEvent::getStartTime, now)
                    .ge(SeckillEvent::getEndTime, now);
        } else if (statusFilter == EventStatus.ENDED) {
            wrapper.lt(SeckillEvent::getEndTime, now);
        }
        wrapper.orderByDesc(SeckillEvent::getStartTime).orderByDesc(SeckillEvent::getId);

        Page<SeckillEvent> result = seckillEventMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        Map<Long, String> productNames = loadProductNames(result.getRecords());
        List<EventVO> records = result.getRecords().stream()
                .map(event -> toVO(event, now, productNames.get(event.getProductId())))
                .collect(Collectors.toList());
        return new PageResult<>(records, result.getTotal(), result.getPages());
    }

    private EventStatus parseStatusFilter(String status) {
        if (status == null || status.trim().isEmpty() || "all".equalsIgnoreCase(status.trim())) {
            return null;
        }
        return EventStatus.fromCode(status.trim())
                .orElseThrow(() -> new RuntimeException("status 只能是 all、pending、running、ended"));
    }

    private Map<Long, String> loadProductNames(List<SeckillEvent> events) {
        Set<Long> productIds = events.stream()
                .map(SeckillEvent::getProductId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (productIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Product::getTitle, (a, b) -> a));
    }

    // ==================== 接口 3：修改 / 下线 ====================

    @Override
    public EventVO updateEvent(Long id, UpdateEventRequest request) {
        if (id == null) {
            throw new RuntimeException("活动ID不能为空");
        }
        if (request == null || request.isEmpty()) {
            throw new RuntimeException("至少需要修改一个字段");
        }

        SeckillEvent event = seckillEventMapper.selectById(id);
        if (event == null) {
            throw new RuntimeException("活动不存在");
        }
        LocalDateTime now = LocalDateTime.now();

        // 契约 §1.3：endTime <= now 视为已下线，不可再改
        if (!event.getEndTime().isAfter(now)) {
            throw new RuntimeException("活动已结束，不可修改");
        }

        boolean running = !now.isBefore(event.getStartTime());

        // 下线：任何未结束的活动都允许只把 endTime 改到当前或过去。
        // 未开始的活动走 applyPendingUpdate 会因「结束时间必须晚于当前时间」被拒，
        // 而建错的活动如果撤不掉会一直占用该商品的未结束名额。
        boolean downline = request.getEndTime() != null
                && !request.getEndTime().isAfter(now)
                && request.getSeckillPrice() == null
                && request.getStock() == null
                && request.getStartTime() == null;

        if (downline) {
            event.setEndTime(request.getEndTime());
            // 未开始活动下线后，起止时间会倒挂（start > end），状态推导会误判成 pending。
            // 把开始时间收到结束时间，保证落库状态自洽。
            if (event.getStartTime().isAfter(request.getEndTime())) {
                event.setStartTime(request.getEndTime());
            }
        } else if (running) {
            // 进行中：只允许改 endTime，用于下线
            if (request.getSeckillPrice() != null || request.getStock() != null
                    || request.getStartTime() != null) {
                throw new RuntimeException("进行中的活动只能修改结束时间");
            }
            if (!request.getEndTime().isAfter(event.getStartTime())) {
                throw new RuntimeException("结束时间必须晚于开始时间");
            }
            event.setEndTime(request.getEndTime());
        } else {
            applyPendingUpdate(event, request, now);
        }

        if (seckillEventMapper.updateById(event) != 1) {
            throw new RuntimeException("修改活动失败，请稍后重试");
        }
        refreshPreheatIfPresent(event);
        return toVO(event, LocalDateTime.now(), null);
    }

    /**
     * 已预热过的活动，改完数据库后同步 Redis，否则下线/改时间不会生效。
     * 未预热的活动不主动创建窗口——预热仍是显式操作。
     * 刷新失败时删除窗口 key（fail-closed）：宁可提示未预热，也不按旧窗口继续放行。
     */
    private void refreshPreheatIfPresent(SeckillEvent event) {
        try {
            if (!seckillEventCache.windowExists(event.getProductId())) {
                return;
            }
            EventStatus status = EventStatus.of(
                    event.getStartTime(), event.getEndTime(), LocalDateTime.now());
            if (status == EventStatus.PENDING) {
                // 未开始的活动不会有成交，库存和时间可以一起刷新
                seckillEventCache.preheat(event);
            } else {
                seckillEventCache.writeWindow(event);
            }
        } catch (RuntimeException e) {
            log.error("刷新秒杀预热数据失败，删除窗口 key：eventId={}, productId={}",
                    event.getId(), event.getProductId(), e);
            try {
                seckillEventCache.deleteWindow(event.getProductId());
            } catch (RuntimeException ex) {
                log.error("删除活动窗口失败：productId={}", event.getProductId(), ex);
            }
        }
    }

    private void applyPendingUpdate(SeckillEvent event, UpdateEventRequest request, LocalDateTime now) {
        Product product = productMapper.selectById(event.getProductId());
        if (product == null) {
            throw new RuntimeException("商品不存在");
        }

        BigDecimal price = request.getSeckillPrice() != null
                ? request.getSeckillPrice() : event.getSeckillPrice();
        Integer stock = request.getStock() != null
                ? request.getStock() : event.getStock();
        LocalDateTime start = request.getStartTime() != null
                ? request.getStartTime() : event.getStartTime();
        LocalDateTime end = request.getEndTime() != null
                ? request.getEndTime() : event.getEndTime();

        if (price == null || price.compareTo(product.getPrice()) >= 0) {
            throw new RuntimeException("秒杀价必须低于商品原价");
        }
        int availableStock = availableStockOf(product.getId());
        if (stock == null || stock <= 0 || stock > availableStock) {
            throw new RuntimeException("秒杀库存必须在 1 到商品库存之间");
        }
        if (start == null || end == null) {
            throw new RuntimeException("活动时间不能为空");
        }
        if (!start.isBefore(end)) {
            throw new RuntimeException("开始时间必须早于结束时间");
        }
        if (!end.isAfter(now)) {
            throw new RuntimeException("结束时间必须晚于当前时间");
        }
        // 同一商品不允许存在另一场未结束活动；排除自身
        SeckillEvent conflict = seckillEventMapper.selectUnfinishedByProductId(
                event.getProductId(), now, event.getId());
        if (conflict != null) {
            throw new RuntimeException("该商品已存在未结束的活动（id=" + conflict.getId() + "）");
        }

        event.setSeckillPrice(price);
        event.setStock(stock);
        event.setStartTime(start);
        event.setEndTime(end);
    }

    // ==================== 公共 ====================

    private EventVO toVO(SeckillEvent event, LocalDateTime now, String productName) {
        return EventVO.builder()
                .id(event.getId())
                .productId(event.getProductId())
                .productName(productName)
                .seckillPrice(event.getSeckillPrice())
                .stock(event.getStock())
                .startTime(event.getStartTime())
                .endTime(event.getEndTime())
                .status(EventStatus.of(event.getStartTime(), event.getEndTime(), now).getCode())
                .build();
    }

    /**
     * 可售库存的唯一真相源是 product_stock（消费者实际扣减的那张表）。
     * product.stock 只是历史遗留的展示字段，不参与任何校验。
     */
    private int availableStockOf(Long productId) {
        ProductStock stock = productStockMapper.selectByProductId(productId);
        return stock == null || stock.getStock() == null ? 0 : stock.getStock();
    }

    private EventVO replay(String key) {
        AdminIdempotency record = adminIdempotencyMapper.selectById(key);
        if (record == null) {
            return null;
        }
        try {
            EventVO vo = objectMapper.readValue(record.getResponseJson(), EventVO.class);
            vo.setReplayed(true);
            return vo;
        } catch (JsonProcessingException e) {
            log.error("幂等记录解析失败：key={}", key, e);
            throw new RuntimeException("幂等记录解析失败，请联系管理员");
        }
    }

    private String toJson(EventVO vo) {
        try {
            return objectMapper.writeValueAsString(vo);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("幂等记录序列化失败，请联系管理员");
        }
    }
}
