package com.example.seckillservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.flashmall.common.entity.Order;
import com.example.flashmall.common.entity.ProductStock;
import com.example.flashmall.common.mapper.OrderMapper;
import com.example.flashmall.common.mapper.ProductStockMapper;
import com.example.seckillservice.service.SeckillService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Override
    @Transactional
    public Long seckill(Long userId, Long productId) {
        ProductStock stock = productStockMapper.selectOne(
                new LambdaQueryWrapper<ProductStock>()
                        .eq(ProductStock::getProductId, productId)
        );
        if (stock == null || stock.getStock() <= 0) {
            throw new RuntimeException("库存不足");
        }

        // 2. 乐观锁更新：只有版本号匹配时才扣减成功
        LambdaUpdateWrapper<ProductStock> update = new LambdaUpdateWrapper<>();
        update.eq(ProductStock::getProductId, productId)
                .eq(ProductStock::getVersion, stock.getVersion())
                .setSql("stock = stock - 1, version = version + 1");  // 关键修复：同时更新版本号
        int affected = productStockMapper.update(null, update);
        if (affected == 0) {
            log.warn("乐观锁冲突，秒杀失败 userId={}, productId={}, version={}",
                    userId, productId, stock.getVersion());
            throw new RuntimeException("秒杀失败，请重试");
        }

        // 3. 创建订单（简化版，不写商品名称查询）
        Order order = new Order();
        order.setOrderNo(generateOrderNo());
        order.setUserId(userId);
        order.setProductId(productId);
        order.setProductName("商品" + productId); // 实际应查询商品表
        order.setPrice(new BigDecimal("99.99"));   // 实际应查询
        order.setQuantity(1);
        order.setTotalAmount(order.getPrice());
        order.setStatus(0);
        orderMapper.insert(order);// 插入订单
        log.info("秒杀成功，订单ID={}", order.getId());
        return order.getId();
    }

    private String generateOrderNo() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))
                + (UUID.randomUUID().toString().hashCode() & Integer.MAX_VALUE);
    }

}
