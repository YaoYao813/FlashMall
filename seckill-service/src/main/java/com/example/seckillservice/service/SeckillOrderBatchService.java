package com.example.seckillservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.flashmall.common.entity.Order;
import com.example.flashmall.common.entity.Product;
import com.example.flashmall.common.entity.ProductStock;
import com.example.flashmall.common.entity.SeckillMessage;
import com.example.flashmall.common.mapper.OrderMapper;
import com.example.flashmall.common.mapper.ProductMapper;
import com.example.flashmall.common.mapper.ProductStockMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 同一商品的批量落库。
 *
 * <p>单独成 Bean 而不是放在 {@code SeckillConsumer} 里自调用：
 * 同类方法自调用不走 Spring 代理，{@code @Transactional} 不生效，
 * 会出现「扣了库存、插单失败、库存不回滚」。
 *
 * <p>幂等：MQ 重投（锁竞争 requeue、死信重放）时，先按 order_no 查已存在的订单，
 * 过滤掉已落库的消息再扣库存，避免同一批消息扣两次。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillOrderBatchService {

    private final ProductStockMapper productStockMapper;
    private final OrderMapper orderMapper;
    private final ProductMapper productMapper;
    private final RabbitTemplate rabbitTemplate;

    /**
     * 处理一批同商品消息，返回实际落库的条数（重复投递的消息被跳过）。
     */
    @Transactional(rollbackFor = Exception.class)
    public int processBatchOrders(Long productId, List<SeckillMessage> batch) {
        List<SeckillMessage> fresh = filterExisting(batch);
        if (fresh.isEmpty()) {
            log.info("消息均已落库，跳过：productId={}, count={}", productId, batch.size());
            return 0;
        }
        int count = fresh.size();

        // 1. 查询 MySQL 库存
        ProductStock stock = productStockMapper.selectOne(
                new LambdaQueryWrapper<ProductStock>().eq(ProductStock::getProductId, productId));
        if (stock == null || stock.getStock() < count) {
            throw new RuntimeException("MySQL库存不足，productId=" + productId + ", need=" + count);
        }

        // 2. 乐观锁批量扣减库存（一次 UPDATE）
        int affected = productStockMapper.decrementStock(productId, count, stock.getVersion());
        if (affected == 0) {
            throw new RuntimeException("乐观锁冲突，productId=" + productId);
        }

        // 3. 查询商品真实信息
        Product product = productMapper.selectById(productId);
        String productName = product != null ? product.getTitle() : "秒杀商品-" + productId;
        BigDecimal price = product != null ? product.getPrice() : BigDecimal.ZERO;

        // 4. 批量创建订单（一条 INSERT SQL）
        List<Order> orders = new ArrayList<>(count);
        for (SeckillMessage msg : fresh) {
            Order order = new Order();
            order.setOrderNo(msg.getOrderNo());
            order.setUserId(msg.getUserId());
            order.setProductId(msg.getProductId());
            order.setProductName(productName);
            order.setPrice(price);
            order.setQuantity(1);
            order.setTotalAmount(price);
            order.setStatus(0);
            orders.add(order);
        }
        orderMapper.insertBatch(orders);

        // 5. 发送延迟取消消息（15 分钟后未支付自动取消，回滚库存）
        for (Order order : orders) {
            rabbitTemplate.convertAndSend(
                    "order.delay.exchange",
                    "order.cancel",
                    order.getId(),
                    message -> {
                        message.getMessageProperties().setDelay(15 * 60 * 1000);
                        return message;
                    }
            );
        }
        log.info("已发送延迟取消消息，数量={}, orderNos={}",
                orders.size(), orders.stream().map(Order::getOrderNo).collect(Collectors.toList()));
        return count;
    }

    /**
     * 过滤掉 order_no 已经存在于 order 表的消息。
     * order_no 由 Lua 之前同步生成，同一条消息无论重投几次 order_no 都不变。
     */
    private List<SeckillMessage> filterExisting(List<SeckillMessage> batch) {
        List<String> orderNos = batch.stream()
                .map(SeckillMessage::getOrderNo)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (orderNos.isEmpty()) {
            return batch;
        }
        Set<String> existing = new HashSet<>(orderMapper.selectExistingOrderNos(orderNos));
        if (existing.isEmpty()) {
            return batch;
        }
        List<SeckillMessage> fresh = batch.stream()
                .filter(msg -> !existing.contains(msg.getOrderNo()))
                .collect(Collectors.toList());
        log.info("过滤重复投递消息：total={}, existing={}, fresh={}",
                batch.size(), batch.size() - fresh.size(), fresh.size());
        return fresh;
    }
}
