# FlashMall — 工程约定

基于 Spring Cloud Alibaba 的高并发秒杀商城（Java 1.8 / Spring Boot 2.7.12）。
`agent-service/` 是同仓库新增的 Python Agent 层，不改动 Java 技术栈版本。

## 目录与端口

| 模块 | 端口 | 职责 |
|---|---|---|
| `gateway/` | 8888 | 统一入口、JWT 验签、路由、向下游注入 `X-User-Id` |
| `user-service/` | 8081 | 注册 / 登录 / 登出、收货地址 |
| `shop-service/` | 8082 | 商品与店铺查询、布隆 + 缓存 + 互斥锁 |
| `order-service/` | 8083 | 订单列表 / 详情 / 取消、模拟支付、延迟取消 |
| `seckill-service/` | 8080 | 秒杀主链路：Semaphore → Redis Lua 预减 → MQ → 乐观锁 |
| `common/` | — | 共享实体、Mapper、JWT / 缓存 / 锁工具 |
| `agent-service/` | 8000 | Python Agent 层（FastAPI + LangGraph + MCP） |

## 启动

```bash
docker-compose up -d                        # 基础设施 + 5 个服务（10 容器）
mvn spring-boot:run -pl seckill-service     # 单模块本地跑
```

依赖：MySQL 8 / Redis 7 / RabbitMQ 3.12（延迟消息插件）/ Nacos 2.2.3 / Sentinel 面板。

时间与字符集：业务时间统一按 UTC+8；`docker-compose.yml` 已给 Java 服务与 MySQL 注入 `TZ=Asia/Shanghai`。
`mysql/init/*.sql` 顶部有 `SET NAMES utf8mb4`，手动 source 种子脚本时不要去掉，否则 MySQL 客户端默认
latin1，中文会按 latin1 落库成乱码。

**数据库有两个实例，别弄混**：Docker 里的 MySQL 映射到宿主机 **3308**（容器内仍是 3306），
而各模块 `application.properties` 的默认值是 `localhost:3306`（本地自行安装的 MySQL）。
Docker 服务通过 `DB_HOST=mysql` 连容器；本地 `mvn spring-boot:run` 跑模块时不加环境变量会连到 3306。
执行 `mysql/init` 或 `mysql/migrations` 脚本前，先确认连的是哪一个（`docker port flashmall-mysql`）。

## 硬约束

1. **Agent 不进秒杀主链路**——只在运营、诊断、数据分析侧旁路。
2. **Java 是业务真相源**——`agent-service` 不直连 MySQL / Redis，只走 HTTP 接口。
3. 不改并发控制的任何一环（`seckill.lua`、`SeckillConsumer`、`StockRollbackConsumer`、`decrementStock`）；
   允许在 Lua 之前加前置校验，见 `agent-service/contracts/tools.md` §1.6。
4. 不把计划写成已完成；文档里的数字必须可复现。
5. 密钥只放 `agent-service/.env`（已 gitignore），不提交、不写进示例文件。

## 已知限制（改代码会踩到）

- 业务错误走 `HTTP 200 + body.code=500`（`Result.fail`），**鉴权失败是真正的 HTTP 401**（网关 `GlobalJwtFilter` 直接写 JSON）；调用方两者都要处理
- 秒杀先读 `seckill:event:{productId}` 窗口（未预热 / 未开始 / 已结束），再走 Lua；库存只读 `seckill:stock:{productId}`。
  窗口无 TTL，Redis 清空或未 `reset` 的活动一律返回「活动不存在或未预热」
- `product.stock` 已删除（2026-11-05）；库存唯一真相源是 `product_stock.stock`，
  创建/修改活动的上限校验与预热校验都读它
- `seckill_event.stock` 是活动**总配额**，消费者扣的是 `product_stock.stock`；两者初值可能不一致（商品 7 是 30/50），不影响防超卖
- 身份靠网关透传 `X-User-Id`，业务服务不鉴权；直连 8080–8083 可伪造身份
- 只有创建活动有幂等键；`PUT /{id}` 与 `POST /{id}/stock-sync` 无幂等键
- `processBatchOrders` 已拆到独立 Bean `SeckillOrderBatchService`（2026-11-12），`@Transactional` 真正生效
- 消费者 `nack requeue=false` 会进 `seckill.order.dlq`；死信**只手动重放**
  （`POST /api/admin/seckill/dlq/replay`），不做自动重放
- `seckill.order.queue` 带死信参数，**队列参数不可变更**：改 `RabbitMQConfig` 里的
  `x-dead-letter-*` 必须先 `rabbitmqctl delete_queue seckill.order.queue` 再重启，否则 `PRECONDITION_FAILED`
- 库存对账（`GET /api/admin/seckill/reconcile`）只读、不自动修；`在途` 只统计队列 ready 消息，
  不含已投递未 ack 的那批，高峰时可能瞬时出现假 `DRIFT`，告警要求连续两次才升级。
  告警是单行 JSON `reconcile.alert {...}`，`fingerprint` 只是成因提示，不参与判定
- 对账的 `STALE` 只覆盖「同商品有更新的场次」；若后续测试/手工操作改过同一商品的
  Redis 或 `product_stock`（而该活动本身是最新场次），它会一直报 `DRIFT`——不是代码回归
- `seckill_event.warehouse_baseline` 由预热写入；对账上线前的老活动该列为 NULL，仓库侧返回 `UNKNOWN`
- 库存回滚幂等靠 `stock_rollback_log`（主键 `order_id`，2026-11-12）。**回滚消息必须带
  `orderId`**；老格式（只有 `productId + userId`）会被打 ERROR 后丢弃——宁可少退不可多退
- 回滚执行顺序固定 **先 DB 后 Redis**：DB 退成功、Redis 失败只会「少卖」，差异靠对账发现；
  此时消息 **ack 而不是重投**（Redis 回滚无幂等保护，重投可能多 +1）
- `seckill.stock.rollback.queue` **没有 DLX**，回滚消费者只在 DB 故障时 `requeue=true`
- 订单状态变更一律 CAS（`OrderMapper.casPay` / `casTerminate`，`WHERE status=0`），不要退回 `updateById`
- 取消接口幂等：订单已是「已取消」时再次调用会补发回滚消息并返回成功（消费端按 `orderId` 去重）
- 支付接口查不到订单但订单号前 14 位是 10 分钟内的时间戳 → `body.code=202`（可重试），
  其余仍是 500。注意 **JDK 8 的 `DateTimeFormatter` 解析 `yyyyMMddHHmmssSSS` 紧邻小数域会抛异常**，
  同样的串在 JDK 17 上能解析，别改回 17 位
- 普通订单 `OrderServiceImpl.createOrder` 是死代码，且取消时会错误地给 `product_stock` 加库存；接上前先修
- `user.role` 字段存在但未使用，没有授权体系
- 商品详情存在双层缓存与双层加锁，冷缓存时内层可能自旋超时返回 null

## 开工顺序

新会话接手时按顺序读：`docs/AGENT-SCOPE.md`（做什么）→ `docs/BUILD-PLAN.md`（做到哪、下一步）
→ `agent-service/contracts/tools.md`（接口的唯一真相）。先读再写码。

做什么 / 不做什么以 `docs/AGENT-SCOPE.md` 为准；被砍的想法记进 `IDEAS.md`；
工具接口以 `agent-service/contracts/tools.md` 为准，先写契约再实现两端。
