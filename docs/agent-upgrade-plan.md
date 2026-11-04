# FlashMall → AI Agent 升级方案

> 目标：把现有 Java 秒杀商城改造成「有 AI Agent 能力」的项目，用于投递 Agent / AI 应用开发实习。
> 约束：**复用现有 Java 后端，新增独立 Python Agent 层，技术栈 LangGraph + MCP。**
> 本文所有现状数据来自对仓库代码的实际扫描，不是 README 转述。

---

## 0. 结论先行

- **推荐做「秒杀运营智能助手」为主线，附赠一个只读的「排障子图」**，合成一个项目：**FlashMall Seckill Copilot**。
- 理由：现有后端能复用 90%，缺口（活动 CRUD）恰好能补上那张一直没用的 `seckill_event` 表；既有写操作（可演示 HITL 审批），又有数据分析和异常诊断，Agent 技术栈能完整铺开。
- 不做完整「运维排障平台」：需要先补 Actuator/Prometheus/Loki 一整套可观测体系，工作量翻倍，且容易做成"演示很酷、深度很浅"。
- 不做「用户端客服」：RAG + 工具调用的同质化严重，且后端缺物流、退款、预约三类数据，做出来是假的。

---

## 1. 现状扫描

### 1.1 核心业务模块清单

| 模块 | 端口 | 职责 | 核心类 |
|------|------|------|--------|
| gateway | 8888 | 统一入口、JWT 鉴权、路由、CORS | `GlobalJwtFilter`、`GatewayConfig` |
| user-service | 8081 | 注册/登录/登出、收货地址 | `AuthController`、`UserServiceImpl` |
| shop-service | 8082 | 店铺/商品查询与更新、缓存三防 | `ProductController`、`CacheLockUtil`、`BloomFilterUtil` |
| order-service | 8083 | 订单查询/取消、模拟支付、延迟取消消费 | `OrderServiceImpl`、`OrderTimeoutConsumer` |
| seckill-service | 8080 | 秒杀主链路、Lua 预减、MQ 生产/批量消费、库存回滚、单机限流 | `SeckillServiceImpl`、`SeckillConsumer`、`StockRollbackConsumer` |
| common | - | 实体、Mapper、工具类、全局异常、Redis 配置 | `Result`、`JwtUtil`、`CacheLockUtil` |

**基础设施**：Nacos 注册中心、Redis 缓存/原子操作/分布式锁、RabbitMQ 异步与延迟消息、MySQL 8.0、Sentinel 面板、Docker Compose 10 容器。

**分层结构**：GateWay → Controller → Service → MyBatis-Plus Mapper → MySQL；横切 common 模块（共享实体与工具）。

---

### 1.2 Controller 层 API 清单（共 18 个）

#### user-service（7 个）

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/auth/register` | 注册 | 白名单 |
| POST | `/api/auth/login` | 登录，返回 JWT | 白名单 |
| POST | `/api/auth/logout` | 登出，删 Redis token | `X-User-Id` |
| GET | `/api/address` | 地址列表 | `X-User-Id` |
| POST | `/api/address` | 新增地址 | `X-User-Id` |
| PUT | `/api/address/{id}` | 更新地址 | 仅 JWT，**无归属校验** |
| DELETE | `/api/address/{id}` | 删除地址 | 仅 JWT，**无归属校验** |

#### shop-service（5 个）

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/api/product?page&size&keyword&sort` | 商品分页/搜索/排序 | 需登录 |
| GET | `/api/product/{id}` | 商品详情（布隆 + 缓存 + 互斥锁） | 需登录 |
| PUT | `/api/product` | 更新商品 | 仅 JWT，**无越权校验** |
| GET | `/api/shop/{id}` | 店铺详情（布隆 + 缓存） | 需登录 |
| PUT | `/api/shop` | 更新店铺 | 仅 JWT，**无越权校验** |

#### order-service（5 个）

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/api/orders?page&size` | 我的订单分页 | `X-User-Id` |
| GET | `/api/orders/{orderNo}` | 订单详情 | `X-User-Id` + 归属校验 |
| POST | `/api/orders/{orderId}/cancel` | 取消订单 | `X-User-Id` + 归属校验 |
| POST | `/api/pay/mock?orderId\|orderNo` | 模拟支付 | **无鉴权**（网关白名单只放了 callback，mock 未放） |
| POST | `/api/pay/callback` | 支付回调 | 白名单，**无验签** |

#### seckill-service（1 个）

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/seckill?productId` | 秒杀下单，返回 orderNo | `X-User-Id` + Semaphore |

#### 网关路由

```
/api/auth/**, /api/address/**   → lb://user-service
/api/shop/**, /api/product/**   → lb://shop-service
/api/orders/**, /api/pay/**     → lb://order-service
/api/seckill/**                 → lb://seckill-service
```

#### 关键缺口（做 Agent 必须补）

- ❌ 没有**活动管理**接口（`seckill_event` 表只有实体，无 Mapper/Service/Controller）
- ❌ 没有**库存查询/调整**接口（只能用 SQL 直查）
- ❌ 没有**运营统计**接口（GMV、支付率、库存消耗率）
- ❌ 没有**管理员角色校验**（`user.role` 字段存在但代码从未使用，网关只认证不鉴权）
- ❌ 没有**审计日志**表
- ❌ 没有 Actuator / Prometheus / 链路追踪 / 结构化日志
- ❌ 没有物流、退款、预约相关表与接口

---

### 1.3 核心数据表（7 张）

| 表 | 关键字段 | 说明 |
|----|----------|------|
| `user` | `id`, `username`(唯一), `password`(BCrypt), `phone`, `role`(0买家/1卖家) | `role` 未被使用 |
| `shop` | `id`, `name`, `owner_id`, `status` | 索引 `owner_id` |
| `product` | `id`, `shop_id`, `title`, `price`, `stock`, `status` | 商品主表 |
| `product_stock` | `id`, `product_id`(唯一), `stock`, `version` | 秒杀库存 + 乐观锁 |
| `seckill_event` | `id`, `product_id`, `seckill_price`, `stock`, `start_time`, `end_time` | **代码从未使用** |
| `address` | `id`, `user_id`, `receiver`, `phone`, `province/city/district/detail`, `is_default` | 索引 `user_id` |
| `order` | `id`, `order_no`(唯一), `user_id`, `product_id`, `price`, `quantity`, `total_amount`, `status`(0-5), `pay_time`, `trade_no` | 无双写幂等键、无 item 表 |

**订单状态机**：`0 待支付 → 1 已支付 → 2 已发货 → 3 已完成`；`4 已取消 / 5 超时`。

---

### 1.4 现有中间件

| 中间件 | 版本/端口 | 当前用途 | Agent 升级可用性 |
|--------|-----------|----------|------------------|
| MySQL 8.0 | 3308 | 7 张业务表 | ✅ 统计、对账的数据源 |
| Redis 7 | 6380（AOF） | 秒杀库存、用户标记、token、商品缓存、分布式锁 | ✅ 活动预热、指标计数 |
| RabbitMQ 3.12 | 5672 / 15672 | 异步下单、延迟取消、库存回滚 | ✅ 队列深度可作为排障信号 |
| Nacos 2.2.3 | 8848/9848 | 注册发现 | ⚪ Agent 不直接依赖 |
| Sentinel 1.8.6 | 8858 | **仅接 Dashboard，代码零规则** | ⚠️ 做排障需先补规则与指标 |
| Docker Compose | 10 容器 | 一键启动 | ✅ Agent 可作为第 11 个容器加入 |

---

### 1.5 可直接被 Agent 调用的业务能力

| 能力 | 现有接口 | 可直接用 | 说明 |
|------|----------|----------|------|
| 商品搜索/详情 | `GET /api/product`、`/{id}` | ✅ | 支持 keyword/分页/排序 |
| 店铺详情 | `GET /api/shop/{id}` | ✅ | |
| 我的订单列表/详情 | `GET /api/orders`、`/{orderNo}` | ✅ | 用户维度，天然适合客服场景 |
| 取消订单 | `POST /api/orders/{id}/cancel` | ✅ | 有归属校验 |
| 秒杀下单 | `POST /api/seckill` | ⚠️ | 写操作、有副作用，Agent 不应直接触发 |
| 模拟支付 | `POST /api/pay/mock` | ⚠️ | 演示可用，生产不可 |
| 用户登录 | `POST /api/auth/login` | ✅ | Agent 需拿用户 token 才能调用户维度接口 |
| 地址管理 | `/api/address` | ✅ | 但缺少归属校验，Agent 调用要自己加约束 |
| **创建/调整活动** | 无 | ❌ | 必须新增 |
| **库存查询/调整/预热** | 无 | ❌ | 必须新增 |
| **运营统计** | 无 | ❌ | 必须新增 |
| **异常诊断数据** | 无 | ❌ | 需新增指标接口或直连 Redis/RabbitMQ |

**调用约定上的三个坑（Agent 层必须处理）**：

1. **HTTP 恒为 200**：`GlobalExceptionHandler` 把业务异常统一转成 `code=500` 的 200 响应。MCP 工具必须解析 `body.code`，不能只看 status。
2. **身份靠 `X-User-Id` 透传**：业务服务不做鉴权，直接暴露 8080-8083。Agent 必须只走网关，并自己维护可信的 `user_id`，**绝不能从模型输出里取 user_id**。
3. **写接口无幂等键**：Agent 重试可能重复创建订单/活动，必须在 Agent 层或新增接口上加 `Idempotency-Key`。

---

## 2. 目标架构

### 2.1 分层图

```
┌──────────────────────────────────────────────┐
│  Web Chat UI / CLI / 飞书机器人（可选）         │
└───────────────────┬──────────────────────────┘
                    │ HTTP + SSE（流式输出）
┌───────────────────▼──────────────────────────┐
│  Python Agent Service（FastAPI）              │
│  ├─ LangGraph 编排                            │
│  │   ├─ Router（意图识别）                     │
│  │   ├─ 活动运营子图（写操作 + HITL 审批）      │
│  │   ├─ 数据分析子图（只读）                    │
│  │   ├─ 库存预警子图（定时 + 主动推送）          │
│  │   └─ 排障子图（只读诊断）                    │
│  ├─ Checkpointer（Redis，会话/中断恢复）        │
│  ├─ RAG（秒杀规则/运营 SOP，pgvector）          │
│  └─ Guardrails（工具白名单/参数校验/审计）       │
└───────────────────┬──────────────────────────┘
                    │ MCP（stdio / streamable-http）
┌───────────────────▼──────────────────────────┐
│  MCP Servers（Python）                        │
│  ├─ commerce-mcp：商品/活动/库存/订单工具       │
│  ├─ ops-mcp：Redis/MQ/DB/日志只读诊断工具       │
│  └─ kb-mcp：规则文档检索（可选，也可内嵌）       │
└───────────────────┬──────────────────────────┘
                    │ HTTP + JWT（只走网关）
┌───────────────────▼──────────────────────────┐
│  Java 后端（现有 5 服务 + 新增 admin 接口）     │
│  Gateway 8888 → user/shop/order/seckill        │
└───────────────────┬──────────────────────────┘
                    │
         MySQL / Redis / RabbitMQ
```

### 2.2 为什么是「Python 独立层 + MCP」，而不是 Java 里塞 LLM

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| Java 内嵌 Spring AI | 部署简单 | 与目标岗位技术栈不符；LangGraph 生态在 Python；面试官看不到 Agent 工程能力 | ❌ |
| Python 直接调 HTTP（无 MCP） | 最快 | 工具与业务耦合，无法展示协议设计；换客户端要重写 | ⚠️ 可作 v0.1 |
| **Python Agent + MCP Server** | 工具契约清晰、可被任意 MCP 客户端复用、权限边界显式 | 多一跳、多一层维护 | ✅ 推荐 |

**核心叙事**：Java 负责"可靠交易"，Python 负责"智能决策"，中间用 MCP 做**受控的能力边界**。这句话本身就是面试亮点。

### 2.3 推荐目录结构

```
flashmall-agent/
├─ app/
│  ├─ main.py                 # FastAPI + SSE
│  ├─ config.py
│  ├─ graph/
│  │   ├─ state.py            # TypedDict 状态定义
│  │   ├─ builder.py          # StateGraph 组装
│  │   ├─ router.py
│  │   ├─ nodes/              # intent / clarify / plan / act / verify / respond
│  │   └─ subgraphs/
│  │       ├─ activity.py
│  │       ├─ analytics.py
│  │       └─ diagnose.py
│  ├─ mcp_servers/
│  │   ├─ commerce_server.py
│  │   └─ ops_server.py
│  ├─ clients/                # Java API 封装（含 code 解析、重试、幂等键）
│  ├─ guardrails/             # 参数校验、权限、注入防护
│  ├─ rag/
│  └─ audit/
├─ evals/                     # 30-50 条金标用例
├─ tests/
├─ docker-compose.agent.yml
└─ pyproject.toml
```

---

## 3. 三个方向评估

### 3.1 方向一：秒杀运营智能助手

#### 核心功能

1. **自然语言建活动**："下周三上午 10 点，Java 核心思想秒杀，50 件，9.9 元，每人限 1 件" → 槽位抽取 → 规则校验 → 建 `seckill_event` → 预热 Redis。
2. **活动调整**：改时间/库存/价格、延期、提前下线。
3. **活动数据查询**：GMV、订单数、支付率、库存消耗率、Top 商品。
4. **库存预警**：低库存、库存与订单不一致、预热缺失；可定时巡检并主动推送。
5. **异常自动处置**：检测到超卖/异常 → 暂停活动 → 生成处置方案 → 人工确认 → 执行回滚。
6. **运营日报**：按天汇总并生成自然语言报告。

#### 需要新增的 Agent 模块

| 模块 | 作用 |
|------|------|
| Intent + Slot 抽取 | 自然语言 → 结构化活动配置（structured output / Pydantic） |
| 业务规则校验节点 | 时间合法、价格低于原价、库存不超过可售、限购 > 0 |
| 活动管理子图 | 查询/创建/修改/上下线，写操作前 `interrupt()` 审批 |
| 数据分析子图 | 调用统计接口，生成表格 + 结论 |
| 库存预警子图 | 定时触发（APScheduler/Cron），异常时进入诊断 |
| HITL 审批 | LangGraph `interrupt()` + 恢复，前端展示待确认卡片 |
| 审计模块 | 每次工具调用落库：谁、何时、什么参数、结果 |

#### 对接现有后端的方式

新增 Java 侧（seckill-service 或独立 admin-service）：

```
GET    /api/admin/seckill/events?status&page
GET    /api/admin/seckill/events/{id}
POST   /api/admin/seckill/events
PUT    /api/admin/seckill/events/{id}
POST   /api/admin/seckill/events/{id}/publish
POST   /api/admin/seckill/events/{id}/offline
GET    /api/admin/seckill/stocks?productIds=
POST   /api/admin/seckill/stocks/{productId}/adjust
POST   /api/admin/seckill/stocks/{productId}/preheat
GET    /api/admin/seckill/stats?eventId&from&to
GET    /api/admin/orders/stats?eventId
```

配套改动：
- 新增 `SeckillEventMapper/Service/Controller`，让 `seckill_event` 表真正被使用；
- 新增 `role=2 ADMIN` 或独立 `admin` 角色，网关增加鉴权（不只是认证）；
- Agent 用**服务身份**（独立 JWT，`role=agent`）调用 admin 接口，携带 `operator_id` 与 `Idempotency-Key`。

MCP 工具（约 12 个）：
`list_activities` / `get_activity` / `create_activity` / `update_activity` / `publish_activity` / `offline_activity` / `get_stock` / `adjust_stock` / `preheat_stock` / `get_event_stats` / `get_order_stats` / `search_ops_rules`

#### 开发工作量

| 工作包 | Claude Code 人日 | 你的理解时间 |
|--------|------------------|--------------|
| Java admin 接口 + 角色鉴权 + 审计表 | 2.5–3.5 | 0.5 |
| MCP server + 工具 schema | 1–1.5 | 0.5 |
| LangGraph 编排 + 子图 + checkpointer | 3–4 | 1.5 |
| HITL + 幂等 + 审计 | 1.5–2 | 0.5 |
| 评测集 + 演示脚本 + 文档 | 1.5–2 | 1 |
| **合计** | **9.5–13** | **4** |

**评级：中**

#### 技术亮点与简历竞争力

- 展示 **NL → 结构化配置 → 规则校验 → 写操作** 的完整闭环，不是问答机器人。
- HITL 审批 + 幂等 + 审计，体现"AI 不能乱动生产数据"的工程判断。
- 复用秒杀库存预热链路，故事自洽。
- 可量化：活动创建从"改库/写脚本"变成一句话；异常处置从人工 10 分钟降到 30 秒。

**竞争力：★★★★☆**（业务闭环最强，最像企业真实项目）

---

### 3.2 方向二：秒杀运维排障助手

#### 核心功能

1. **实时监控**：QPS、错误率、P99、Redis 库存、MQ 堆积、DB 连接数。
2. **异常检测与告警**：阈值 + 同比/环比，异常时触发诊断。
3. **超卖排查**：对账 Redis / MySQL / 订单表，定位差异。
4. **接口报错分析**：按异常类型聚合，用 orderNo 串起全链路日志。
5. **限流熔断效果分析**：Semaphore 排队、Sentinel 拒绝数、熔断状态。
6. **根因假设 + 优化建议**：结合 runbook RAG 给出可执行建议。
7. **自动处置**：扩容消费者、暂停活动、重放死信（高危，必须 HITL）。

#### 需要新增的 Agent 模块

| 模块 | 作用 |
|------|------|
| 指标采集层 | Actuator + Micrometer + Prometheus；或直接查 Redis/MySQL |
| 日志层 | 结构化 JSON 日志 + traceId（Micrometer Tracing） |
| 诊断图 | 症状 → 假设列表 → 证据采集 → 排除 → 结论 → 建议 |
| Runbook RAG | 历史故障处置手册检索 |
| 告警入口 | Alertmanager webhook → Agent 自动开诊断会话 |
| 事故报告生成 | 时间线、影响面、根因、改进项 |

#### 对接现有后端的方式

**当前后端没有任何可观测设施**，必须先补：

- 所有服务加 `spring-boot-starter-actuator` + `micrometer-registry-prometheus`；
- 暴露 `/actuator/prometheus`；
- Compose 增加 Prometheus + Grafana（+ 可选 Loki）；
- RabbitMQ 用 15672 Management API 查队列深度；
- 对账用只读 SQL（`product_stock` vs `order` 汇总 vs Redis `seckill:stock:*`）。

MCP 工具（约 10 个）：
`get_service_metrics` / `get_error_summary` / `get_slow_endpoints` / `get_mq_depth` / `get_redis_stock` / `reconcile_stock` / `query_logs` / `get_limiter_stats` / `search_runbook` / `propose_remediation`

#### 开发工作量

| 工作包 | Claude Code 人日 | 你的理解时间 |
|--------|------------------|--------------|
| Actuator + 指标 + traceId + 结构化日志 | 3–4 | 1 |
| Prometheus + Grafana + Loki 编排 | 2–3 | 1 |
| ops MCP server | 1.5–2 | 0.5 |
| 诊断图 + runbook RAG | 3–4 | 1.5 |
| 故障注入脚本 + 演示 | 2 | 1 |
| 评测 + 文档 | 2 | 1 |
| **合计** | **13.5–17** | **6** |

**评级：高**

#### 技术亮点与简历竞争力

- AIOps + RCA，技术深度最高，面试可聊的空间最大。
- 与"监控告警"结合，天然有生产价值。
- 但对实习简历有个风险：**大部分时间在补监控基础设施，而不是 Agent**；且诊断质量难量化，容易被追问"你怎么证明它诊断对了"。

**竞争力：★★★★☆**（深度最强，但投入产出比最低，容易烂尾）

---

### 3.3 方向三：用户端智能客服

#### 核心功能

1. **规则问答（RAG）**：秒杀时间、限购、支付时效、退款规则。
2. **订单查询**：按 orderNo 查状态与时间线。
3. **物流查询**：需新建 mock 物流数据。
4. **退款申请**：需新建退款流程与状态机。
5. **活动预约提醒**：需新建订阅表 + 定时通知。
6. **人工转接 / 工单**。
7. **多轮上下文 + 用户身份绑定**。

#### 需要新增的 Agent 模块

| 模块 | 作用 |
|------|------|
| RAG 管线 | 文档切分、embedding、向量库、重排、引用 |
| 客服图 | 意图 → 检索/工具 → 回答 → 转人工 |
| 会话记忆 | 多轮上下文 + 用户偏好 |
| 安全护栏 | 不承诺赔付、不改价、敏感信息脱敏、超范围转人工 |
| 反馈闭环 | 点赞/点踩 → 评测集 |

#### 对接现有后端的方式

- 订单查询直接复用现有 2 个接口；
- 物流、退款、预约**都要新建**（表 + 接口 + 状态机），否则只能编数据；
- MCP 工具：`get_order` / `list_orders` / `get_logistics` / `apply_refund` / `subscribe_activity` / `search_rules` / `handoff_to_human`。

#### 开发工作量

| 工作包 | Claude Code 人日 | 你的理解时间 |
|--------|------------------|--------------|
| RAG 管线 + 知识库整理 | 2–2.5 | 1 |
| 客服图 + 记忆 + 转人工 | 3 | 1 |
| 物流/退款/预约业务补齐 | 3–4 | 1 |
| MCP server | 1–1.5 | 0.5 |
| 护栏 + 评测 + 文档 | 2 | 1 |
| **合计** | **11–13** | **4.5** |

**评级：中**

#### 技术亮点与简历竞争力

- RAG + Function Calling + 转人工，是标准客服 Agent 形态。
- **同质化最严重**：十份 AI 实习简历里至少三份是客服机器人。
- 后端缺三类核心数据，补业务的时间超过做 Agent 的时间，故事容易变成"我做了个假的退款流程"。

**竞争力：★★☆☆☆**（技术不难，差异化最低）

---

### 3.4 三方向对比

| 维度 | 运营助手 | 运维排障 | 智能客服 |
|------|----------|----------|----------|
| 复用现有后端 | 高（90%） | 中（60%，需补监控） | 中（50%，需补物流退款） |
| 新增 Java 工作量 | 中 | 高 | 高 |
| Agent 技术密度 | 高（工具编排 + HITL + 结构化输出） | 很高（RCA + RAG + 多源证据） | 中（RAG + 工具调用） |
| 业务闭环 | **强**（创建→预热→监控→处置） | 强（发现→诊断→建议） | 弱（问答为主） |
| 可演示性 | **高**（一句话建活动） | 中（需要造故障） | 高（聊天即可） |
| 简历差异化 | **高** | 高 | 低 |
| 烂尾风险 | 低 | **高** | 中 |
| 工作量（CC 人日） | 9.5–13 | 13.5–17 | 11–13 |
| 推荐度 | **★★★★★** | ★★★☆☆ | ★★☆☆☆ |

---

## 4. 推荐方案

### 4.1 推荐：方向一为主线 + 方向二的「只读排障子图」（轻量版）

**项目名建议**：**FlashMall Seckill Copilot — 基于 LangGraph + MCP 的秒杀运营 Agent**

**功能边界（v1）**：

1. 自然语言创建/修改/上下线秒杀活动（写，HITL）
2. 库存查询、调整、预热（写，HITL）
3. 活动数据查询与日报（只读）
4. 库存/超卖异常巡检 + 只读诊断（只读）
5. 诊断结论 + 处置建议；处置动作需人工确认

**为什么是这个组合**：

- **复用最大化**：Java 侧只补 admin CRUD 和一两个统计接口，不动秒杀主链路，风险低。
- **故事完整**：运营说一句话 → Agent 建活动 → 预热 Redis → 定时巡检 → 发现库存异常 → 给出根因 → 人工确认暂停。一条线讲完，面试官能听懂。
- **技术栈覆盖全**：LangGraph 状态机 + 子图 + checkpointer + `interrupt()` + MCP 工具协议 + 结构化输出 + RAG（规则检索）+ 评测 + 可观测。
- **避开了两个坑**：不用先建 Prometheus/Loki 全家桶；不做烂大街的客服机器人。
- **可演示**：现场输入一句话建活动，再用脚本制造一次"Redis 库存与 DB 不一致"，让 Agent 诊断出来——5 分钟演示，比 PPT 有说服力。

### 4.2 四周路线（按每天 2–3 小时人机协作估算）

| 周 | 目标 | 产出 | 你要理解的内容 |
|----|------|------|----------------|
| 第 1 周 | Java 侧补齐 + MCP 打通 | 5 个 admin 接口、commerce-mcp 可被 MCP Inspector 调用 | MCP 协议、工具 schema、为什么工具要幂等 |
| 第 2 周 | LangGraph 最小闭环 | 一句话建活动（含校验 + 审批 + 落库 + 预热） | StateGraph、节点/边、checkpointer、`interrupt()` |
| 第 3 周 | 数据 + 诊断子图 | 活动统计、库存巡检、只读诊断 | 工具编排、结构化输出、RAG 何时该用/不该用 |
| 第 4 周 | 工程化 + 评测 | 30 条金标、Langfuse 追踪、Docker 编排、README + 演示视频 | 评测指标、成本/延迟、护栏设计 |

**v0.1 最小可交付（约 5–6 CC 人日）**：3 个只读工具 + 1 个活动创建工具 + 单图（router → tool → respond），先跑通再迭代。

> **范围说明（2026-11-04）**：本文的「四周路线」是完整目标，不是本轮范围。
> 本轮 V1 只取 §4.2 里的 v0.1 切片，硬截止 2026-11-13 23:59，验收标准见 `docs/AGENT-SCOPE.md`。

---

## 5. 工作量评估（Claude Code 为主，你主要负责理解）

### 5.1 汇总

| 方案 | Claude Code 人日 | 你的理解/复习时间 | 日历周期（每天 2–3h） |
|------|------------------|-------------------|------------------------|
| 方向一（推荐主线） | 9.5–13 | 4 天 | 3–4 周 |
| 方向一 + 轻量排障 | 12–15 | 5 天 | 4 周 |
| 方向二（完整） | 13.5–17 | 6 天 | 5–6 周 |
| 方向三 | 11–13 | 4.5 天 | 4 周 |

> "CC 人日"指 Claude Code 连续执行 + 你 review 的等效工作日，不含你从零学 LangGraph 的时间。

### 5.2 你必须理解的最小集（面试会问）

1. **LangGraph**：State / Node / Edge / Conditional Edge / Subgraph / Checkpointer / `interrupt()` 恢复机制。
2. **MCP**：Host / Client / Server 三角色；tools vs resources vs prompts；stdio 与 streamable-http 的区别；工具描述为什么影响调用准确率。
3. **工具设计**：为什么工具要幂等、要参数校验、要分只读/写/高危；为什么不给 LLM 一个"执行任意 SQL"的工具。
4. **HITL**：什么操作必须审批；审批状态怎么持久化；用户拒绝后图怎么走。
5. **上下文工程**：上下文里放什么、工具返回怎么裁剪、长会话怎么压缩。
6. **评测**：工具调用准确率、槽位填充准确率、任务成功率、P95 延迟、单次成本。
7. **安全**：提示注入（商品标题/用户输入是数据不是指令）、越权、脱敏、审计。
8. **边界**：Agent 不进秒杀主链路；Java 仍是业务真相源。

### 5.3 建议砍掉的东西

- ❌ 前端不要从零写：用 Gradio / Streamlit / 现成 Chat UI，1 天搞定。
- ❌ 不要做多 Agent 协作：除非单 Agent 已经跑通，否则是纯负担。
- ❌ 不要自研向量库/rerank：Chroma + bge-m3 足够。
- ❌ 不要接真实支付/物流：mock 即可，面试官关心的是架构不是对接。

---

## 6. 风险与避坑

| 风险 | 后果 | 对策 |
|------|------|------|
| 让 Agent 直连业务库执行 SQL | 注入 + 越权 + 不可审计 | 只暴露参数化工具，禁止 raw SQL |
| 写操作无幂等 | 重试导致重复活动/订单 | `Idempotency-Key` + 唯一索引 |
| 从模型输出取 user_id | 越权 | user_id 只从会话上下文取 |
| HTTP 200 掩盖错误 | 工具误判成功 | 统一解析 `body.code` |
| 工具过多（>20） | 调用准确率下降 | 按子图裁剪工具集 / 工具检索 |
| 只做 Demo 不做评测 | 面试一问就露馅 | 30 条金标 + 失败案例分析 |
| Agent 进秒杀主链路 | 延迟爆炸 | 明确旁路，只在运营/诊断侧 |

---

## 7. 一句话总结

**用最小的 Java 改动（活动 CRUD + 统计 + 权限），换最大范围的 Agent 能力展示（LangGraph 编排 + MCP 工具 + HITL + 评测），做「秒杀运营 Copilot」，而不是又一个客服机器人。**
