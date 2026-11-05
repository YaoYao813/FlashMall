-- =========================================================
-- FlashMall Agent 层管理接口：建表脚本
-- 依据：agent-service/contracts/tools.md §0.4
-- 说明：只新增两张表，不改动现有 7 张表；IF NOT EXISTS，可重复执行
--
-- 新环境：mysql/init 已挂载到 docker-entrypoint-initdb.d，首次启动自动执行
-- 已有库：手动跑一次（PowerShell 不支持 < 重定向，用 docker cp + source）
--   docker cp .\mysql\init\03-admin.sql flashmall-mysql:/tmp/admin.sql
--   docker exec -i flashmall-mysql mysql -uroot -proot123 -e "source /tmp/admin.sql"
-- =========================================================

USE flashmall;

CREATE TABLE IF NOT EXISTS `admin_idempotency` (
  `idempotency_key` VARCHAR(128) NOT NULL COMMENT '幂等键，格式 thread_id:tool_call_id',
  `response_json`   TEXT         NOT NULL COMMENT '首次请求的完整响应体，用于回放',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次请求时间',
  PRIMARY KEY (`idempotency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 写操作幂等表';

CREATE TABLE IF NOT EXISTS `admin_audit_log` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '审计记录ID',
  `idempotency_key` VARCHAR(128) DEFAULT NULL COMMENT '关联的幂等键',
  `tool_name`       VARCHAR(64)  NOT NULL COMMENT '工具名，如 create_seckill_event',
  `operator_id`     BIGINT       NOT NULL COMMENT '操作人（管理员）ID',
  `params_json`     TEXT         COMMENT '调用参数',
  `result_json`     TEXT         COMMENT '执行结果',
  `approved`        TINYINT      NOT NULL DEFAULT 0 COMMENT '用户是否在确认卡片上批准：1 是，0 否',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
  PRIMARY KEY (`id`),
  KEY `idx_created` (`created_at`),
  KEY `idx_tool` (`tool_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 写操作审计日志';
