# FlashMall · 秒杀商城 + 运营侧 AI Agent

> Java 负责主链路的正确性，Python Agent 负责让运营「说一句话」就能干活 —— 两边用一份 HTTP 契约隔开，Agent 不进秒杀链路。

秒杀商城本体是 Spring Cloud Alibaba 微服务：Redis Lua 原子预减 → MQ 削峰 → 乐观锁落库，四道闸防超卖。
同仓库的 `agent-service/` 用 LangGraph + MCP 把「建活动、调库存、看数据、查库存漂移」变成对话，
**但写操作必须人工确认**：幂等键由代码生成、由 Java 按键回放，模型既看不到写工具的参数，也拿不到键。

| 指标 | 实测数字 |
|---|---|
| 秒杀防超卖 | 库存 50、100 个不同用户并发抢 → **50 成功 / 50 售罄**，Redis 与 `product_stock` 同时归零，订单恰好 50 条 |
| Agent 决策准确率 | 30 条用例 × 3 次 = 90 次调用：开发集 **75/75**；留存集首轮 12/15（含 1 条期望写错）→ 按正确期望 **15/15** |
| 跨 run 一致率 | **100%**（温度 0，3 次运行的「工具 + 参数 + 是否弹卡片」完全一致） |
| 写操作安全 | 误动作率 **0%**；用户拒绝后审计表零新增；同幂等键重放 `replayed=true`，不重复执行 |
| 单轮延迟 | P50 **811 ms** / P95 **1206 ms**（`deepseek-chat`，温度 0，含工具调用） |
| 回归 | Java 单测 **102** 条 · Python 单测 **39** 条 · 端到端验收 **81** 项（34 / 21 / 26）全绿 |

数字均可复现：评测命令见 [评测](#评测)，验收脚本见 `scripts/acceptance_m2.py` / `_m3` / `_m4`。

## 它解决什么问题

秒杀活动是运营驱动的：临时加库存、改价格、看「这场卖得怎么样」、怀疑库存对不上。
在后台，这意味着 5 个接口、一次 JWT 登录、一堆必填字段，还要记住哪个接口会刷新 Redis 窗口。

Agent 把这件事变成一句话，且不牺牲安全：

| 用户说 | Agent 做 |
|---|---|
| 「现在有哪些进行中的活动」 | 调 `query_seckill_events`，列出 id / 商品 / 价格 / 库存 / 时间窗 |
| 「帮我建个活动，商品 7，下周三上午十点到十二点，50 件，299 块 9」 | 抽槽位 → **弹确认卡片** → 批准后创建并预热 |
| 「商品 7 卖得怎么样」 | 调 `get_seckill_stats`，给 GMV / 支付率 / 消耗率 / DB 与 Redis 库存 |
| 「检查一下库存对不对」 | 逐个活动比对「Redis 剩余」与「DB 剩余」，输出可疑活动与差值 |

三种情况它不动手：**槽位不全**（追问缺哪个字段）、**槽位非法**（开始时间 = 结束时间，批了 Java 也会拒）、
**用户只是在陈述**（「活动 32 好像卖完了」不该被当成修改指令）。

## 架构

```
  运营 / 客服
      │
      ▼
  ┌ Web 对话台  http://127.0.0.1:8000
      │  POST /api/chat（SSE） · /api/chat/{tid}/resume · /state
      ▼
  ┌ agent-service :8000
  │    FastAPI → LangGraph 状态机
  │      router ─┬─▶ clarify     缺槽位 / 槽位非法 → 追问，不弹卡片
  │              ├─▶ read        3 个只读工具
  │              └─▶ plan_write → confirm(interrupt) → execute → respond
  │    checkpointer：redis-stack :6381（只存会话，与业务库存无关）
      │  MCP over stdio，5 个工具
      ▼
  ┌ commerce-mcp
      │  HTTP + JWT，只走网关
 ═════╪═══════════════════════════════════════════════  业务真相源分界线
      ▼
  ┌ Gateway :8888（JWT 验签、路由、注入 X-User-Id）
      ├──▶ user-service    :8081
      ├──▶ shop-service    :8082    布隆过滤 + 双层缓存
      ├──▶ order-service   :8083    延迟消息取消
      └──▶ seckill-service :8080 ──▶ MySQL :3308 · Redis :6380 · RabbitMQ :5672
```

三条硬边界（代码和文档里都这么执行）：

- **Agent 不直连业务库**：不连 MySQL、不连秒杀用的 Redis（:6380），只走网关 HTTP。
  唯一的 Redis 是自己的 `redis-stack`（:6381），只存会话 checkpointer，与业务库存无关。
- **Agent 不进秒杀主链路**：抢购仍然走 Semaphore → Lua 预减 → MQ → 乐观锁，一行没改。
  Agent 只是「人」的替代品去调 admin 接口，删掉 `agent-service/` 主链路照常工作。
- **Java 是业务真相源**：创建活动的库存上限校验、预热、幂等、审计都在 Java 侧，Agent 不做二次判断。

## 快速开始

前置：Docker Desktop（≥4 GB 可用内存）、Python 3.10+。

```bash
# 1) 起基础设施 + 5 个 Java 服务（首次需先下载 Sentinel Dashboard）
cd sentinel && bash download.sh && cd ..
docker compose up -d --build          # 11 个容器，首次约 5 分钟

# 2) 装 Agent 依赖并填配置（key 只放 .env，已 gitignore）
cd agent-service
python -m venv .venv && .venv/Scripts/activate    # macOS/Linux: .venv/bin/activate
pip install -e ".[dev]"
cp .env.example .env                  # 填 LLM_API_KEY / ADMIN_USERNAME / ADMIN_PASSWORD

# 3) 起对话台
uvicorn app.main:app --port 8000      # 打开 http://127.0.0.1:8000
```

浏览器打开后直接说「现在有哪些进行中的活动」即可。想先验命令行：`python -m app.cli`。

Java 侧单独回归：

```bash
mvn -q -pl seckill-service -am test                                  # 102 条
python scripts/acceptance_m2.py && python scripts/acceptance_m3.py   # 34 + 21
python scripts/acceptance_m4.py                                      # 26（需 Agent 与 Java 都在跑）
```

## 演示脚本（5 分钟）

1. 「现在有哪些进行中的活动」→ 列表
2. 「帮我建个活动，商品 7，下周三上午十点到十二点，50 件，299 块 9」→ 确认卡片 → 批准 → 自动预热
3. 「商品 7 卖得怎么样」→ GMV / 支付率 / 消耗率
4. `redis-cli -p 6380 INCRBY seckill:stock:{productId} 3` 人为制造一次 Redis 与 DB 不一致
   → 「检查一下库存对不对」→ 诊断指出活动 id 与差值 → 调整 → 确认 → 修复
5. 打开审计表：每一次写操作有谁、何时、什么参数、批没批

## 评测

评测只测**决策层**（选对工具 / 抽对参数 / 该不该追问），不打真后端：

- 30 条用例 = 工具选择 12 · 参数抽取 10 · 该追问 8，按 **开发集 25 / 留存集 5** 分开
- 每条跑 3 次（温度 0），共 90 次调用
- **为什么用桩工具箱**：真后端状态会变（活动过期、库存被抢、同商品建第二次被拒），同一份用例跑两次可能一成功一失败，数字就不可复现。桩只给读工具返回固定形状的数据；写工具根本不会被调用——图在确认卡片处挂起，评测只判「卡片拼得对不对」

```bash
cd agent-service
python -m evals.run_eval                    # 开发集 25 × 3
python -m evals.run_eval --split holdout    # 留存集 5 × 3
```

| 指标 | 含义 |
|---|---|
| 准确率（按 run） | 工具名、参数、是否动手，全部对得上才算过 |
| 跨 run 一致率 | 3 次的「工具 + 参数 + 是否弹卡片」签名完全相同才算一致 |
| 字段级准确率 | 参数抽取类逐字段比对（时间按 datetime 比，金额容忍 `299.9` / `299.90`） |
| 误动作率 | 「该追问」的用例却挂起了确认卡片或调了写工具 —— **必须为 0** |

| 数据集 | 规模 | 准确率 | 跨 run | 误动作率 | P50 / P95 |
|---|---|---|---|---|---|
| 开发集 | 25 × 3 | 75/75 | 100% | 0% | 811 / 1206 ms |
| 留存集（首轮，原期望） | 5 × 3 | 12/15 | 100% | — | — |
| 留存集（修正期望后重跑） | 5 × 3 | 15/15 | 100% | 0% | 858 / 1126 ms |

留存集首轮的 3 次失败全部来自**用例自己写错**：说「第一个卖得怎么样」，期望是「追问活动 id」，
而系统正确地把上一轮列表里的「1. 活动 32」解析成 `eventId=32` 去查了——系统比用例写得好。
只改了期望、没改代码和提示词，但留存集因此已被看过，**不能再当泛化能力的证明**，只当回归探针。

报告：[`evals/report-dev.md`](agent-service/evals/report-dev.md) · [`evals/report-holdout.md`](agent-service/evals/report-holdout.md)

**评测最值钱的地方是它抓到的 bug**，不是那个 100%：

1. `cl-02`「活动 32 加 5 件」：用户没说原因，模型把动作句「加 5 件」当成 `reason` 填了，
   槽位校验因此放行、直接弹卡片。误动作率 13.3%。修法：提示词规则写明「reason 只填用户明说的原因」。
2. `cl-06`「十点开始、十点结束」：槽位齐全但时间窗长度为零，卡片照样弹出，而 Java 必然拒。
   误动作率 16.7%。修法：`schemas.validate_slots()` 做跨字段校验（价格 > 0、库存 > 0、结束晚于开始、
   调整量 ≠ 0），非法与缺槽位一样走追问。**能写成确定性护栏的，绝不靠提示词。**

## 设计决策

**1. 为什么 Agent 在 Python，不在 Java 里（Spring AI / LangChain4j）**
Spring AI 现行版本要求 Spring Boot 3.x + JDK 17，本仓库锁定 Boot 2.7.12 / JDK 8；更关键的是迭代节奏不同——
提示词、评测集、模型一天能改十几次，不该跟业务服务一起发版。代价是要维护一份跨语言契约
（`agent-service/contracts/tools.md`），收益是两边能各自单测、各自回归。

**2. 为什么不走 tool-calling 循环（`ToolNode` + 模型自主调工具）**
router 一次调用直接输出 `intent + slots`，工具由确定性代码映射，模型**看不到写工具的 schema、也拿不到幂等键**，
注入面因此小得多；失败也分得清（分类错 / 抽参错 / 护栏拦下）。代价是不支持模型自己拼参数、不支持多工具串联。

**3. 为什么幂等键必须在确认卡片之前生成**
`interrupt()` 恢复时节点从第一行重跑，interrupt 之前的写入不会持久化。键若在 confirm 里生成，
每次恢复都是新键，Java 就会重复创建活动、重复加库存。所以键在 `plan_write` 生成并存进 checkpoint，
`execute` 阶段原样传给 Java；Java 侧查 `admin_idempotency` 回放，返回 `replayed=true`。

**4. 为什么写操作一定要人工确认**
秒杀活动的错误是**钱**的错误，不是「重问一次」的错误。确认卡片把 Java 校验前的最后一次人工兜底放在
用户眼前：商品名、原价 vs 秒杀价、数量、起止时间、原因、会影响谁。拒绝 → 不产生任何写请求、审计表零新增。

**不做的**：RAG / 向量库（秒杀场景牵强）、多 Agent 与多子图（单图已够讲，子图判据是「独立状态机 +
独立失败模式」，目前只有库存对账满足）、K8s（实习项目里的纯堆料）。理由与判据记在
`docs/OPTIMIZATION-BACKLOG.md`。

## 已知限制

写在这里的每一条都是真的，不是免责声明：

1. **关键词兜底从未被验证**。`router_source` 分布是 `llm 81 / unknown 9 / keyword 0`——
   90 次评测调用里兜底一次都没触发。它只是保险丝，不能说它「有效」。
2. **相对时间没进评测**。「明天下午三点」「下周三」依赖运行时当前时间，无法复现，只在人工验收里验过；
   评测用例的时间全是绝对时间。
3. **留存集只能当探针**。5 条已被看过、改过一次期望；5/5 的 95% 置信下界约 57%（Wilson），
   一个用例就是 20 个百分点。真实泛化能力没有证据，只有回归状态。
4. **一句话两个动作只执行第一个**。评测用例 `sl-09` 暴露的：第二个动作被静默丢弃，不报错也不追问。
5. **业务错误与鉴权错误不是一种形态**。业务错误是 `HTTP 200 + body.code=500`，鉴权失败才是真正的
   HTTP 401——调用方两种都要处理，这是历史设计，Agent 层已按此实现。
6. **直连 8080–8083 可以伪造身份**。身份靠网关透传 `X-User-Id`，业务服务本身不鉴权；演示环境下成立，
   生产环境需要服务间认证。

## 文档索引

| 文档 | 内容 |
|---|---|
| [`docs/AGENT-SCOPE.md`](docs/AGENT-SCOPE.md) | 做什么 / 不做什么 / 判据 / 验收标准 |
| [`agent-service/contracts/tools.md`](agent-service/contracts/tools.md) | 接口与工具的**唯一真相**，Java 与 Python 都照它写 |
| [`docs/BUILD-PLAN.md`](docs/BUILD-PLAN.md) | 里程碑、每一步的真实数字、踩过的坑、评测纪律 |
| [`docs/OPTIMIZATION-BACKLOG.md`](docs/OPTIMIZATION-BACKLOG.md) | 加分项清单与「明确不做」的理由 |
| [`agent-service/evals/report-dev.md`](agent-service/evals/report-dev.md) | 开发集评测报告（含模型、温度、commit、用例集哈希） |

---

# 附录：秒杀主链路（Java）

基于 Spring Cloud Alibaba 微服务架构的高并发秒杀系统，11 个容器一键启动。

## 架构图

```
┌────────────────────────────────────────────────────────────────┐
│                        用户 / 浏览器                            │
└─────────────────────────────┬──────────────────────────────────┘
                              │  http://localhost:8888
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                   Gateway (API 网关 :8888)                       │
│          Spring Cloud Gateway + JWT 鉴权 + Sentinel 流控          │
└──────┬──────────┬──────────┬──────────┬─────────────────────────┘
       │          │          │          │
       ▼          ▼          ▼          ▼
┌──────────┐┌──────────┐┌──────────┐┌──────────────┐
│  user-   ││  shop-   ││  order-  ││  seckill-    │
│  service ││  service ││  service ││  service     │
│  :8081   ││  :8082   ││  :8083   ││  :8080       │
│  用户服务 ││  商品服务 ││  订单服务 ││  秒杀服务     │
│  ┌─────┐ ││  ┌─────┐ ││  ┌─────┐ ││  ┌────────┐ │
│  │ JWT  │ ││  │布隆  │ ││  │延迟  │ ││  │Lua脚本 │ │
│  │ 鉴权  │ ││  │过滤器│ ││  │消息  │ ││  │库存预减│ │
│  └─────┘ ││  └─────┘ ││  └─────┘ ││  │Semaphore│ │
│          ││          ││          ││  │限流     │ │
└──────┬───┘└────┬─────┘└────┬─────┘└──────┬───────┘
       │         │         │         │
       └─────────┼─────────┼─────────┘
                 │         │
     ┌───────────▼──┐  ┌──▼──────────┐  ┌──────────────────┐
     │    MySQL     │  │    Redis    │  │    RabbitMQ      │
     │    :3308     │  │    :6380    │  │    :5672         │
     │   8.0        │  │   7-alpine  │  │   3.12-mgmt      │
     │   (主机端口)   │  │   (主机端口)  │  │   (延迟消息插件)   │
     └──────────────┘  └─────────────┘  └──────────────────┘

     ┌──────────────┐  ┌──────────────────┐  ┌────────────────────┐
     │    Nacos     │  │    Sentinel      │  │  redis-stack :6381 │
     │    :8848     │  │    Dashboard     │  │  仅 Agent 会话使用   │
     │   v2.2.3     │  │    :8858         │  │  (JSON + Search)   │
     └──────────────┘  └──────────────────┘  └────────────────────┘
```

## 技术栈

| 层级 | 技术 |
|------|------|
| 框架 | Spring Boot 2.7 + Spring Cloud 2021 + Spring Cloud Alibaba 2021 |
| 注册中心 | Nacos 2.2.3 |
| 网关 | Spring Cloud Gateway（WebFlux 响应式） |
| 数据库 | MySQL 8.0 + MyBatis-Plus |
| 缓存 | Redis 7 + Redisson 分布式锁 |
| 消息队列 | RabbitMQ 3.12（延迟消息插件） |
| 流控/熔断 | Sentinel 1.8.6 |
| 认证 | JWT（jjwt 0.9.1） |
| 密码加密 | BCrypt（strength 10） |
| 文档 | SpringDoc（Swagger） |
| 容器化 | Docker + Docker Compose |
| 测试 | JUnit 5 + Mockito |
| Agent 层 | Python 3.10+ · FastAPI · LangGraph · MCP（stdio）· DeepSeek |

## 秒杀核心链路

```
请求进入 → 活动窗口校验(Redis seckill:event:{pid}) → Semaphore限流(200并发)
         → Redis Lua脚本预减库存 → 返回订单号(异步) → MQ削峰 → 批量消费(50条/批)
         → Redisson分布式锁 → 乐观锁扣减MySQL → 延迟消息自动取消
```

### 防超卖方案
- **Redis Lua 原子预减**：在 Redis 中预扣库存，Lua 脚本保证原子性
- **MySQL 乐观锁**：`UPDATE ... SET stock = stock - 1 WHERE id = ? AND stock >= ?`
- **分布式锁**：Redisson 对同一商品加锁，串行化库存扣减
- **布隆过滤器**：预先过滤不存在的商品 ID

### 接口示例

```bash
# 注册用户
curl -X POST http://localhost:8888/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"123456"}'

# 登录（获取 Token）
curl -X POST http://localhost:8888/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testbuyer","password":"123456"}'

# 查询商品（需要 Token）
curl http://localhost:8888/api/product \
  -H "Authorization: Bearer <TOKEN>"

# 秒杀下单
curl -X POST "http://localhost:8888/api/seckill?productId=1" \
  -H "Authorization: Bearer <TOKEN>" \
  -H "X-User-Id: 1"
```

管理接口（Agent 调用的就是这 5 个，契约见 `agent-service/contracts/tools.md`）：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/admin/seckill/events` | 创建活动（需 `Idempotency-Key`） |
| GET | `/api/admin/seckill/events` | 活动列表 |
| PUT | `/api/admin/seckill/events/{id}` | 修改 / 下线 |
| POST | `/api/admin/seckill/events/{id}/stock-sync` | 预热 / 增量调整库存（`increment` 认幂等键） |
| GET | `/api/admin/seckill/events/{id}/stats` | 统计快照 |

## 管理界面

| 服务 | 地址 | 账号 |
|------|------|------|
| Nacos | http://localhost:8848/nacos | nacos / nacos |
| RabbitMQ | http://localhost:15672 | admin / 123456 |
| Sentinel | http://localhost:8858 | sentinel / sentinel |
| Agent 对话台 | http://127.0.0.1:8000 | 无需登录（Agent 用种子管理员账号调接口） |

### 各个微服务的 Swagger

| 服务 | Swagger UI |
|------|------------|
| 秒杀服务 | http://localhost:8888/api/seckill/swagger-ui.html |
| 用户服务 | http://localhost:8081/swagger-ui.html |
| 订单服务 | http://localhost:8083/swagger-ui.html |

## 项目结构

```
FlashMall/
├── common/                    # 公共模块（实体、Mapper、JWT / 缓存 / 锁工具）
├── gateway/                   # API 网关（JWT 验签、路由、注入 X-User-Id）
├── user-service/              # 用户服务（注册、登录、地址管理）
├── shop-service/              # 店铺/商品服务（布隆 + 缓存 + 互斥锁）
├── order-service/             # 订单服务（创建、支付、延迟取消）
├── seckill-service/           # 秒杀服务（Lua 预减、MQ 削峰、乐观锁、admin 接口 1–5）
├── agent-service/             # Python Agent 层
│   ├── app/                   # config / clients / graph（router→clarify/read/write）/ guardrails
│   ├── mcp_servers/           # commerce-mcp：5 个工具，stdio
│   ├── contracts/tools.md     # 接口与工具的契约（两端都照它写）
│   ├── evals/                 # 30 条用例 + 评测脚本 + 报告
│   ├── tests/                 # pytest 36 条
│   └── web/index.html         # 单页对话台（SSE + 确认卡片）
├── mysql/init/                # 建表 + 种子数据 + admin 幂等/审计表
├── scripts/                   # acceptance_m2 / m3 / m4.py
├── docs/                      # AGENT-SCOPE / BUILD-PLAN / OPTIMIZATION-BACKLOG
├── Dockerfile                 # 多阶段构建（Maven 编译 → JRE 运行）
├── docker-compose.yml         # 11 个容器的编排
└── pom.xml                    # 父 POM（依赖版本管理）
```

## 数据库表

| 表名 | 说明 |
|------|------|
| `user` | 用户表（BCrypt 加密） |
| `shop` | 店铺表 |
| `product` | 商品表 |
| `product_stock` | 秒杀库存表（含乐观锁版本号）——库存的唯一真相源 |
| `seckill_event` | 秒杀活动表（`stock` 是活动总配额） |
| `address` | 收货地址表 |
| `order` | 订单表 |
| `admin_idempotency` | 管理接口幂等记录（键 → 回放响应） |
| `admin_audit_log` | 写操作审计（谁、何时、什么参数、批没批） |

## 环境变量

所有 Java 服务通过 Docker 环境变量配置，无需修改配置文件。详见 `docker-compose.yml`。

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `NACOS_ADDR` | nacos:8848 | Nacos 地址 |
| `DB_HOST` | mysql | 数据库主机 |
| `DB_PASSWORD` | root123 | 数据库密码 |
| `REDIS_HOST` | redis | Redis 主机 |
| `RABBITMQ_HOST` | rabbitmq | RabbitMQ 主机 |
| `JWT_SECRET` | flashmall-secret-key-2024 | JWT 签名密钥 |

Agent 层的配置在 `agent-service/.env`（不入库）：`LLM_API_KEY`、`LLM_BASE_URL`、`LLM_MODEL`、`FLASHMALL_BASE_URL`、
`ADMIN_USERNAME`、`ADMIN_PASSWORD`、`REDIS_URL`（指向 `redis-stack` :6381，且必须用 db 0）。
