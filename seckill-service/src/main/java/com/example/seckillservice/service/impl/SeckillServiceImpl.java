package com.example.seckillservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.flashmall.common.entity.Order;
import com.example.flashmall.common.entity.ProductStock;
import com.example.flashmall.common.mapper.OrderMapper;
import com.example.flashmall.common.mapper.ProductStockMapper;
import com.example.seckillservice.script.SeckillRedisScript;
import com.example.seckillservice.service.SeckillService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Slf4j
@Service
public class SeckillServiceImpl implements SeckillService {

    @Autowired
    private ProductStockMapper productStockMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private SeckillRedisScript seckillRedisScript;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // 秒杀业务
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long seckill(Long userId, Long productId) {

        // ========== 第一阶段：Redis + Lua 预校验（内存操作，微秒级） ==========
        Long result = seckillRedisScript.executeSeckill(productId, userId);
        if (result == 1) {
            throw new RuntimeException("库存不足");
        }
        if (result == 2) {
            throw new RuntimeException("您已抢购过该商品，请勿重复下单");
        }

        // ========== 第二阶段：数据库落单（只有通过校验的请求才到这里） ==========
        try {
            // 1. 查询 MySQL 库存（兜底校验，防止 Redis 与 MySQL 数据不一致）
            LambdaQueryWrapper<ProductStock> query = new LambdaQueryWrapper<ProductStock>()
                    .eq(ProductStock::getProductId, productId);
            ProductStock stock = productStockMapper.selectOne(query);
            if (stock == null || stock.getStock() <= 0) {
                // Redis 有库存但 MySQL 无库存 → 数据不一致，回滚 Redis
                seckillRedisScript.rollbackStock(productId, userId);
                throw new RuntimeException("库存不足");
            }

            // 2. 乐观锁扣 MySQL 库存
            LambdaUpdateWrapper<ProductStock> update = new LambdaUpdateWrapper<>();
            update.eq(ProductStock::getProductId, productId)
                    .eq(ProductStock::getVersion, stock.getVersion())
                    .setSql("stock = stock - 1, version = version + 1");
            int affected = productStockMapper.update(null, update);
            if (affected == 0) {
                // 乐观锁冲突，回滚 Redis
                seckillRedisScript.rollbackStock(productId, userId);
                throw new RuntimeException("秒杀失败，请重试");
            }

            // 3. 创建订单
            Order order = new Order();
            order.setOrderNo(generateOrderNo());
            order.setUserId(userId);
            order.setProductId(productId);
            order.setProductName("秒杀商品-" + productId);
            order.setPrice(new BigDecimal("0.01"));
            order.setQuantity(1);
            order.setTotalAmount(order.getPrice());
            order.setStatus(0);
            orderMapper.insert(order);
            return order.getId();
        } catch (RuntimeException e) {
            // 任何数据库异常都回滚 Redis 库存
            seckillRedisScript.rollbackStock(productId, userId);
            throw e;
        }
    }

    // 创建订单号
    private String generateOrderNo() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))
                + (UUID.randomUUID().toString().hashCode() & Integer.MAX_VALUE);
    }

}
