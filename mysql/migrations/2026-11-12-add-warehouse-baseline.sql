-- 给 seckill_event 加 warehouse_baseline（预热那一刻的仓库库存，记作 D0）。
--
-- 为什么要这一列：
--   对账依赖两条不变量，两条都含「订单数 - 取消数」这个净消耗量：
--     配额侧：seckill_event.stock       - redisStock         = O - X + 在途
--     仓库侧：seckill_event.warehouse_baseline - product_stock.stock = O - X + 在途
--   没有 D0 就只能查配额侧，查不出「仓库少了货、却没有对应订单」这类问题。
--   基线放 MySQL 而不放 Redis：Redis 重启恰恰是最需要基线的时候。
--
-- 适用：已有数据库（新环境由 mysql/init/01-schema.sql 直接建出该列）。
-- 执行：
--   docker exec -i flashmall-mysql mysql -uroot -proot123 < mysql/migrations/2026-11-12-add-warehouse-baseline.sql
--
-- 可重复执行：列已存在时不做任何事。
-- 历史活动该列保持 NULL，对账返回 UNKNOWN，不会误报。

SET NAMES utf8mb4;
USE flashmall;

SET @column_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'flashmall'
      AND TABLE_NAME = 'seckill_event'
      AND COLUMN_NAME = 'warehouse_baseline'
);

SET @ddl := IF(@column_exists = 0,
               'ALTER TABLE `seckill_event` ADD COLUMN `warehouse_baseline` INT NULL COMMENT ''预热时的仓库库存基线（D0），用于双向对账'' AFTER `stock`',
               'SELECT ''seckill_event.warehouse_baseline 已存在，跳过'' AS message');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
