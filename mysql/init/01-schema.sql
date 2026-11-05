-- FlashMall 数据库建表脚本（Docker MySQL 首次启动时自动执行）
-- 使用 CREATE TABLE IF NOT EXISTS 确保幂等
-- 显式声明连接字符集，避免中文列注释/默认值被按 latin1 存储
SET NAMES utf8mb4;

USE flashmall;

-- 1. 用户表
CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username` VARCHAR(64) NOT NULL COMMENT '用户名',
    `password` VARCHAR(128) NOT NULL COMMENT '密码（BCrypt加密）',
    `phone` VARCHAR(20) DEFAULT NULL COMMENT '手机号',
    `email` VARCHAR(128) DEFAULT NULL COMMENT '邮箱',
    `role` INT DEFAULT 0 COMMENT '角色：0=买家, 1=卖家',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 2. 店铺表
CREATE TABLE IF NOT EXISTS `shop` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '店铺ID',
    `name` VARCHAR(128) NOT NULL COMMENT '店铺名称',
    `description` VARCHAR(512) DEFAULT NULL COMMENT '店铺描述',
    `owner_id` BIGINT NOT NULL COMMENT '店主用户ID',
    `status` INT DEFAULT 1 COMMENT '状态：0=关闭, 1=营业',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_owner_id` (`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺表';

-- 3. 商品表
CREATE TABLE IF NOT EXISTS `product` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '商品ID',
    `shop_id` BIGINT NOT NULL COMMENT '所属店铺ID',
    `title` VARCHAR(256) NOT NULL COMMENT '商品标题',
    `price` DECIMAL(10,2) NOT NULL COMMENT '商品价格',
    `description` VARCHAR(1024) DEFAULT NULL COMMENT '商品描述',
    `status` INT DEFAULT 1 COMMENT '状态：1=上架, 0=下架',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_shop_id` (`shop_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

-- 4. 秒杀库存表（含乐观锁版本号）
CREATE TABLE IF NOT EXISTS `product_stock` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `product_id` BIGINT NOT NULL COMMENT '商品ID',
    `stock` INT NOT NULL DEFAULT 0 COMMENT '秒杀库存',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_product_id` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀库存表';

-- 5. 秒杀活动表
CREATE TABLE IF NOT EXISTS `seckill_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '活动ID',
    `product_id` BIGINT NOT NULL COMMENT '商品ID',
    `seckill_price` DECIMAL(10,2) NOT NULL COMMENT '秒杀价格',
    `stock` INT NOT NULL DEFAULT 0 COMMENT '秒杀库存',
    `start_time` DATETIME NOT NULL COMMENT '开始时间',
    `end_time` DATETIME NOT NULL COMMENT '结束时间',
    PRIMARY KEY (`id`),
    KEY `idx_product_id` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀活动表';

-- 6. 收货地址表
CREATE TABLE IF NOT EXISTS `address` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '地址ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `receiver` VARCHAR(64) NOT NULL COMMENT '收货人',
    `phone` VARCHAR(20) NOT NULL COMMENT '联系电话',
    `province` VARCHAR(32) DEFAULT NULL COMMENT '省份',
    `city` VARCHAR(32) DEFAULT NULL COMMENT '城市',
    `district` VARCHAR(32) DEFAULT NULL COMMENT '区/县',
    `detail` VARCHAR(256) DEFAULT NULL COMMENT '详细地址',
    `is_default` INT DEFAULT 0 COMMENT '是否默认：1=是, 0=否',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收货地址表';

-- 7. 订单表（`order` 是 MySQL 保留字，需要反引号）
CREATE TABLE IF NOT EXISTS `order` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '订单ID',
    `order_no` VARCHAR(64) NOT NULL COMMENT '订单编号',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `product_id` BIGINT NOT NULL COMMENT '商品ID',
    `product_name` VARCHAR(256) DEFAULT NULL COMMENT '商品名称',
    `price` DECIMAL(10,2) NOT NULL COMMENT '单价',
    `quantity` INT DEFAULT 1 COMMENT '数量',
    `total_amount` DECIMAL(10,2) NOT NULL COMMENT '总金额',
    `status` INT DEFAULT 0 COMMENT '状态：0=待支付, 1=已支付, 2=已发货, 3=已完成, 4=已取消, 5=超时',
    `address_id` BIGINT DEFAULT NULL COMMENT '收货地址ID',
    `pay_time` DATETIME DEFAULT NULL COMMENT '支付时间',
    `trade_no` VARCHAR(64) DEFAULT NULL COMMENT '交易流水号',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_product_id` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';
