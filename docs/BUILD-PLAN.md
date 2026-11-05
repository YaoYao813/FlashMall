# 施工计划（V1）

> 给任何一次新会话看的交接文档。**开工前先读完 §0，再动任何文件。**

## 0. 开工必读（按顺序）

1. `CLAUDE.md` —— 工程约定、端口、硬约束、已知坑
2. `docs/AGENT-SCOPE.md` —— 做什么 / 不做什么 / 判据 / 验收标准
3. `agent-service/contracts/tools.md` —— 接口与工具的**唯一真相**，实现照它写
4. 本文件 —— 当前进度到哪、下一步做什么

**新会话开场白模板：**

```
读 CLAUDE.md、docs/AGENT-SCOPE.md、docs/BUILD-PLAN.md、
agent-service/contracts/tools.md，然后告诉我当前在哪个里程碑、
下一步的第一个任务是什么。先不要写代码。
```

## 1. 当前状态

| 项 | 状态 |
|---|---|
| 仓库与脚手架 | ✅ 2026-11-04 提交 `新增 Agent 层脚手架与开发约定` |
| Java admin 接口 | ✅ 接口 1–5 完成 11/05（95 条单测通过；M1 验收 19/19、M2 34/34、M3 21/21；删 `product.stock` 后复跑 M2 34/34、M3 21/21） |
| 秒杀前置校验 | ✅ 11/05 完成（活动窗口 Redis 校验，100 并发零超卖） |
| Python 客户端 / MCP | ⬜ 未开始 |
| 图与确认卡片 | ⬜ 未开始 |
| 评测 | ⬜ 未开始 |
| README / 演示 | ⬜ 未开始 |

硬截止：**2026-11-13 23:59**。一旦有面试，暂停开发去准备面试。

## 2. 里程碑

### M1（11/05–11/06）Java 接口 1–3 + 迁移 SQL

**产出**
- `mysql/init/03-admin.sql`（`admin_idempotency`、`admin_audit_log`；幂等脚本，新环境自动执行，已有库手动跑一次）
- seckill-service：`AdminSeckillController`、`AdminSeckillService`、`SeckillEventMapper`、`EventVO`、`EventStatus` 推导
- 接口 1 创建（含幂等回放）、2 列表、3 修改/下线

**验收（2026-11-04 完成）**
- `mvn -pl seckill-service test` 通过（55 条）
- 用 curl 打通：创建 → 重复同 key 返回 `replayed:true` 且**不新增记录** → 列表能看到 → 改价成功 → 已开始的活动改价被拒
- 同一商品建第二场未结束活动被拒
- 端到端脚本 19/19 通过，含：缺幂等键被拒、超库存被拒、非法 status 被拒、无 token 返回 401、中文商品名不乱码

**11/04 验收中修掉的两个环境问题**
- 容器 JVM 默认 UTC，活动状态整体偏 8 小时 → `docker-compose.yml` 给全部 Java 服务与 `mysql` 加 `TZ: Asia/Shanghai`
- MySQL 客户端默认 latin1，种子数据里的中文按 latin1 落库成乱码 → `mysql/init/*.sql` 顶部加 `SET NAMES utf8mb4`
- 接口 3 补充：未开始的活动也允许只传 `endTime` 下线（原实现会被「结束时间必须晚于当前时间」拒绝，导致建错的活动撤不掉）

**数据现状（2026-11-04 实测，开工前必读）**
- 8 个商品全部已有 `endTime=2027-12-31` 的活动 → 直接创建任何商品都会被「已有未结束活动」拒绝。
  验收时先 `PUT` 把商品 7 的种子活动下线（`endTime=now`），再创建。
- 商品 7 的 `product.stock=30`，而 `product_stock.stock=50`、种子活动 `stock=50`。
  契约的 `stock <= product.stock` 按 30 校验，所以演示脚本里的「50 件」改为 **30 件**；
  若坚持 50 件，需先定清「商品库存」以 `product` 还是 `product_stock` 为准并统一数据。
- **后续统一（2026-11-05）**：创建/修改活动的上限校验已改为读 `product_stock.stock`；
  `product.stock` 列已删除（建表/种子脚本同步更新，已有库执行
  `mysql/migrations/2026-11-05-drop-product-stock.sql`）

**不许破坏**：现有 18 个接口、`seckill.lua`、`SeckillConsumer`、`StockRollbackConsumer`

### M2（11/07）接口 4 + 秒杀前置校验

**产出**
- `POST /stock-sync`（`reset` / `increment`）
- `SeckillServiceImpl.seckill()` 开头加活动窗口校验（读 `seckill:event:{productId}`，一次 GET）
- `RedisKeyUtil` 增加按 `productId` 取活动 key 的方法，删掉或改掉原来按活动 id 取的那套，**不留两套**

**验收**
- `reset` 后 Redis 有 `seckill:stock:{pid}` 与 `seckill:event:{pid}`
- 活动未开始 / 已结束 / 未预热三种情况，秒杀接口分别返回对应中文提示
- `increment +10` 后 DB 与 Redis 同步加 10；`increment -999` 被拒
- **回归**：正常预热的商品仍能秒杀成功、重复抢购被拒、库存不足被拒

**验收结果（2026-11-05 完成）**
- 单测 85 条全绿（新增 `StockSyncServiceImplTest` 15 条、`SeckillEventCacheTest` 8 条、接口 3 预热刷新 4 条、秒杀前置校验 3 条）
- `scripts/acceptance_m2.py` 34/34 通过，含：reset 两个 key、仓库不足拒绝预热、未开始/已结束/未预热三种提示、increment ±、下线刷新窗口
- **并发回归**：商品 2 库存 50、100 个不同用户同时抢 → 恰好 50 成功 / 50 库存不足，Redis 与 `product_stock` 均归零，订单恰好 50 条
- 实现口径：窗口 `{eventId,startTs,endTs}` 用 epoch 毫秒、无 TTL；`reset` 先校验 `product_stock` 剩余、再写库存、最后写窗口；接口 3 改时间/库存后刷新已存在的窗口，刷新失败删 key（fail-closed）；`dbStock=event.stock`（活动总配额），`redisStock` 为当前剩余
- 已知数据口径：`event.stock` 是总配额，消费者扣的是 `product_stock`；两者初值可能不同（如商品 7 为 30/50），不影响防超卖；`reset` 已加仓库校验兜住「活动配额 > 仓库剩余」
- **M3 已定**：`GMV = 已支付订单数 × event.seckill_price`。原因：消费者落库的 `order.price` 取的是 `product.price`（原价），不能直接 `SUM(order.price)`；消费者属禁改清单

**不许破坏**：防超卖四道闸的任何一环。改完必须手工跑一遍「库存 50、并发 100 人」验证仍零超卖

### M3（11/08）接口 5 + 回归

**产出**
- `GET /{id}/stats`（订单数、支付率、GMV、消耗率、DB/Redis 库存、未支付订单数）
- Redis 不可达时 `redisStock=null`，不返回 0

**验收**
- 造 3 笔订单（1 支付、1 待支付、1 取消），核对 `orderCount/paidCount/gmv/payRate/ordersOutstanding` 全部对得上

**验收结果（2026-11-05 完成）**
- 单测 95 条全绿（新增 `EventStatsServiceImplTest` 10 条）
- `scripts/acceptance_m3.py` 21/21 通过：3 人抢 10 件 → 1 支付 / 1 取消 / 1 待支付 →
  `orderCount=3、paidCount=1、gmv=9.90、payRate=0.3333、ordersOutstanding=1、
  stockTotal=10、stockConsumed=2、consumeRate=0.2、redisStock=8、dbStock=8`
- 口径：`gmv = paidCount × event.seckill_price`（不能 SUM `order.total_amount`，它写的是原价）；
  `stats.dbStock = product_stock.stock`（仓库剩余，与 stock-sync 的 `dbStock=event.stock` 不同义）；
  `stockConsumed = stockTotal - redisStock` 是**净消耗**，取消回滚后会小于订单数
- **验收中发现并修复的既有缺陷**：`order-service` 未配置消息转换器，默认 Java 序列化发出的
  库存回滚消息被 `seckill-service` 的 Jackson 消费者判定为无法转换而直接丢弃，
  导致「取消订单后 Redis 与 `product_stock` 都不回滚」。修复方式：给 order-service 增加
  `Jackson2JsonMessageConverter` Bean（只改生产端，未动 `StockRollbackConsumer`）。
  修复后 M2 验收回归 34/34
- **库存口径统一**：创建/修改活动的上限校验从 `product.stock` 改为 `product_stock.stock`，
  与预热校验同一真相源；`product.stock` 代码不再读取。
  改后 M2 34/34、M3 21/21 回归通过
- **`product.stock` 已删除（2026-11-05）**：`Product` 实体字段、`01-schema.sql`、`02-seed-data.sql`
  同步移除；已有库执行 `mysql/migrations/2026-11-05-drop-product-stock.sql`（幂等，可重复执行）。
  删列后再次复跑：M2 34/34、M3 21/21，商品列表接口正常返回（`Product` JSON 已无 `stock`）

### M4（11/09–11/11）Python：客户端 → MCP → 图 → 页面

**产出顺序**（每步跑通再做下一步）
1. `app/config.py`、`app/clients/flashmall.py`（登录、401 重登、`code=500` 映射、超时重试）
2. `mcp_servers/commerce_server.py`：5 个工具，先只读 3 个
3. `app/graph/state.py`、`nodes/`、`builder.py`：router → 只读分支 → respond
4. 写分支：`interrupt()` + 确认卡片数据结构
5. `app/main.py`：FastAPI + SSE + 静态页
6. `web/index.html`：对话区 + 确认卡片（批准 / 拒绝）+ 工具调用轨迹

**验收**
- MCP Inspector 能列出 5 个工具并逐个调通
- 对话里说「查一下进行中的活动」能返回列表
- 说「建活动」缺参数时**追问而不是瞎建**
- 写操作弹出确认卡片；拒绝后不产生任何写请求（看审计表）
- 杀进程重启后，挂起的确认仍能恢复

**不许破坏**：`contracts/tools.md` 已定的字段名与路径

### M5（11/12）评测 25 条

**产出** `evals/cases.json` + `evals/run_eval.py`

| 类别 | 条数 | 判定 |
|---|---|---|
| 工具选择 | 12 | 选对工具名 |
| 参数抽取 | 8 | 价格 / 数量 / 时间区间全部抽对 |
| 该追问不该动手 | 5 | 缺必填参数时不调用写工具 |

温度 0，跑 3 次统计跨 run 一致率。**失败样例必须逐条分析**，写进 README。

### M6（11/13）README + 演示

- 一页 README：架构图、真实数字、「为什么不用 Spring AI」「为什么不做 RAG / 多 Agent」
- 5 分钟演示脚本（见 §4）
- 录屏

## 3. 每天节奏

| 时间 | 事 |
|---|---|
| 09:30–10:30 | **投递**，雷打不动。有面试优先面试，开发暂停 |
| 上午余下 | 当天的里程碑任务 |
| 晚上 | 30 分钟：把当天改动的接口/工具对照 `contracts/tools.md` 核一遍 |

## 4. 演示脚本（M6 用）

1. 「现在有哪些进行中的活动」→ 列表
2. 「帮我建个活动，商品 7，下周三上午十点到十二点，50 件，299 块 9」→ 确认卡片 → 批准 → 自动预热
3. 「商品 7 卖得怎么样」→ GMV / 支付率 / 消耗率
4. 用脚本制造一次 Redis 与 DB 不一致 → 「检查一下库存对不对」→ 诊断出差值 → 建议调整 → 确认 → 修复
5. 打开审计表：每一次写操作有谁、何时、什么参数、批没批

## 5. 卡住时的判断

| 症状 | 处理 |
|---|---|
| 某个里程碑超时 1 天 | 砍功能不砍验收：先保证接口能跑通，单测后补 |
| 写到 11/11 图还没跑通 | 放弃确认卡片 UI，先用命令行 `input()` 确认——HITL 的机制必须在，界面可以让位 |
| 评测集写不完 25 条 | 降到 15 条也要出数字，**不许只写「已实现评测」不给结果** |
| 有面试 | 立刻停开发，去准备面试 |
