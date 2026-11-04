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
| Java admin 接口 | ⬜ 未开始 |
| 秒杀前置校验 | ⬜ 未开始 |
| Python 客户端 / MCP | ⬜ 未开始 |
| 图与确认卡片 | ⬜ 未开始 |
| 评测 | ⬜ 未开始 |
| README / 演示 | ⬜ 未开始 |

硬截止：**2026-11-13 23:59**。一旦有面试，暂停开发去准备面试。

## 2. 里程碑

### M1（11/05–11/06）Java 接口 1–3 + 迁移 SQL

**产出**
- `mysql/migrations/2026-11-05-admin.sql`（`admin_idempotency`、`admin_audit_log`）
- seckill-service：`AdminSeckillController`、`AdminSeckillService`、`SeckillEventMapper`、`EventVO`、`EventStatus` 推导
- 接口 1 创建（含幂等回放）、2 列表、3 修改/下线

**验收**
- `mvn -pl seckill-service test` 通过
- 用 curl 打通：创建 → 重复同 key 返回 `replayed:true` 且**不新增记录** → 列表能看到 → 改价成功 → 已开始的活动改价被拒
- 同一商品建第二场未结束活动被拒

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

**不许破坏**：防超卖四道闸的任何一环。改完必须手工跑一遍「库存 50、并发 100 人」验证仍零超卖

### M3（11/08）接口 5 + 回归

**产出**
- `GET /{id}/stats`（订单数、支付率、GMV、消耗率、DB/Redis 库存、未支付订单数）
- Redis 不可达时 `redisStock=null`，不返回 0

**验收**
- 造 3 笔订单（1 支付、1 待支付、1 取消），核对 `orderCount/paidCount/gmv/payRate/ordersOutstanding` 全部对得上

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
