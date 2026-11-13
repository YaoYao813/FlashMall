-- 新增库存回滚幂等台账 stock_rollback_log。
--
-- 为什么需要：
--   回滚消息会被重复投递（消费者 nack requeue、死信重放、客户端重试）。
--   原来的回滚是「product_stock.stock = stock + 1」无条件执行，
--   同一条消息来两次就多退一次 → 仓库库存虚高 → 下一场活动预热校验通过 → 真超卖。
--
-- 设计要点：
--   1. 主键 = order_id。回滚的粒度是「订单」，不是「用户」也不是「商品」。
--      用 {productId,userId} 做键会误杀合法回滚：用户取消后标记被删，
--      可以再抢一次、再取消一次，两条消息的参数完全一样。
--   2. 台账写入与 product_stock +1 在同一个本地事务里，保证「记录」和「改数」同成同败。
--   3. 记录不清理，跟订单同生命周期——消息可能被人工重放，保留期必须比它长。
--
-- 适用：已有数据库（新环境由 mysql/init/01-schema.sql 直接建出）。
-- 执行：
--   docker exec -i flashmall-mysql mysql -uroot -proot123 < mysql/migrations/2026-11-12-add-stock-rollback-log.sql
--
-- 可重复执行：表已存在时不做任何事。

SET NAMES utf8mb4;
USE flashmall;

CREATE TABLE IF NOT EXISTS `stock_rollback_log` (
    `order_id` BIGINT NOT NULL COMMENT '订单ID（幂等键）',
    `order_no` VARCHAR(64) DEFAULT NULL COMMENT '订单号，便于人工排查',
    `product_id` BIGINT NOT NULL COMMENT '商品ID',
    `user_id` BIGINT DEFAULT NULL COMMENT '用户ID',
    `reason` VARCHAR(16) DEFAULT NULL COMMENT 'CANCEL / TIMEOUT',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '回滚时间',
    PRIMARY KEY (`order_id`),
    KEY `idx_product_id` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存回滚幂等台账';
