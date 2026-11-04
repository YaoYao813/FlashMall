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

## 硬约束

1. **Agent 不进秒杀主链路**——只在运营、诊断、数据分析侧旁路。
2. **Java 是业务真相源**——`agent-service` 不直连 MySQL / Redis，只走 HTTP 接口。
3. 不改已验证的秒杀链路（Lua 预减、MQ 消费、乐观锁），除非任务明确要求。
4. 不把计划写成已完成；文档里的数字必须可复现。
5. 密钥只放 `agent-service/.env`（已 gitignore），不提交、不写进示例文件。

## 已知限制（改代码会踩到）

- 错误统一 `HTTP 200 + body.code=500`，调用方必须解析响应体，不能看 HTTP 状态码
- 身份靠网关透传 `X-User-Id`，业务服务不鉴权；直连 8080–8083 可伪造身份
- 所有写接口无幂等键
- `seckill_event` 表有实体但无 Mapper / Service / Controller，从未使用
- `processBatchOrders` 被同类方法自调用，`@Transactional` 不生效
- 消费者 `nack requeue=false` 且无死信队列，业务异常会丢消息
- 库存回滚不幂等，`stock + 1` 无上限，并发取消会双回滚
- `user.role` 字段存在但未使用，没有授权体系
- 商品详情存在双层缓存与双层加锁，冷缓存时内层可能自旋超时返回 null

## 决策依据

做什么 / 不做什么以 `docs/AGENT-SCOPE.md` 为准；被砍的想法记进 `IDEAS.md`；
工具接口以 `agent-service/contracts/tools.md` 为准，先写契约再实现两端。
