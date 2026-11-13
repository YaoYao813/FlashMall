package com.example.seckillservice.service;

import com.example.flashmall.common.entity.StockRollbackLog;
import com.example.flashmall.common.entity.StockRollbackMessage;
import com.example.flashmall.common.mapper.ProductStockMapper;
import com.example.flashmall.common.mapper.StockRollbackLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存回滚的幂等执行。
 *
 * <p>回滚消息至少有两个来源（用户取消 / 超时未付），两边都可能重复投递：
 * 超时消费者处理完但 ack 丢失会重投；取消接口被用户重试会重发；
 * 死信重放也是一次重投。原来的实现是「stock = stock + 1」无条件执行，
 * 同一条消息来两次就多退一次，仓库库存虚高，下一场活动预热校验照样通过 → 真超卖。
 *
 * <p>幂等靠 {@code stock_rollback_log.order_id} 主键：INSERT 撞主键说明这次回滚已经
 * 执行过（或正在执行），直接放弃。台账写入与 {@code product_stock + 1} 在同一个
 * 本地事务里，要么都成、要么都败，不会出现「记了账但没退」的中间态。
 *
 * <p><b>为什么不放在订单状态上做标记：</b>回滚有两个来源，订单状态只能表达
 * 「最终态」，看不出一笔库存是谁、什么时候、因为什么退的；出问题时没法对账。
 */
@Slf4j
@Service
public class StockRollbackService {

    @Autowired
    private StockRollbackLogMapper stockRollbackLogMapper;

    @Autowired
    private ProductStockMapper productStockMapper;

    /**
     * 执行一次库存回滚（DB 侧）。
     *
     * @return true=本次真正退回了库存；false=这条回滚之前已经执行过，调用方应跳过 Redis 侧
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean rollbackOnce(StockRollbackMessage msg) {
        StockRollbackLog ledger = new StockRollbackLog();
        ledger.setOrderId(msg.getOrderId());
        ledger.setOrderNo(msg.getOrderNo());
        ledger.setProductId(msg.getProductId());
        ledger.setUserId(msg.getUserId());
        ledger.setReason(msg.getReason());

        try {
            stockRollbackLogMapper.insert(ledger);
        } catch (DuplicateKeyException e) {
            // 主键冲突 = 这个订单的库存已经退过了。不对 Redis 做任何操作，
            // 否则 Redis 会被再 +1 一次（Redis 侧没有幂等保护）。
            return false;
        }

        int affected = productStockMapper.rollbackStock(msg.getProductId());
        if (affected == 0) {
            // 商品行不存在：台账已经记了，但库存没退成。留日志给人工核对，
            // 不抛异常——抛了会无限重投，而这条数据本身已经错了。
            log.error("库存回滚命中 0 行，商品不存在：orderId={}, productId={}",
                    msg.getOrderId(), msg.getProductId());
        }
        return true;
    }
}
