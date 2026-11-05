-- 删除 product.stock。
--
-- 背景：product.stock 只是历史遗留的展示字段，不参与任何扣减；
-- 真实可售库存的唯一真相源是 product_stock.stock（消费者实际扣减的表）。
-- 创建/修改活动的上限校验已改为读 product_stock.stock。
--
-- 适用：已有数据库（新环境由 mysql/init/01-schema.sql 直接建出无该列的表）。
-- 执行：
--   docker exec -i flashmall-mysql mysql -uroot -proot123 < mysql/migrations/2026-11-05-drop-product-stock.sql
--
-- 可重复执行：列不存在时不做任何事。

SET NAMES utf8mb4;
USE flashmall;

SET @column_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'flashmall'
      AND TABLE_NAME = 'product'
      AND COLUMN_NAME = 'stock'
);

SET @ddl := IF(@column_exists > 0,
               'ALTER TABLE `product` DROP COLUMN `stock`',
               'SELECT ''product.stock 已不存在，跳过'' AS message');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
