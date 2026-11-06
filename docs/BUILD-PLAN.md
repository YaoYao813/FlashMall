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
| Java admin 接口 | ✅ 接口 1–5 完成 11/05（102 条单测通过；M1 验收 19/19、M2 34/34、M3 21/21；删 `product.stock` 后复跑 M2 34/34、M3 21/21） |
| 秒杀前置校验 | ✅ 11/05 完成（活动窗口 Redis 校验，100 并发零超卖） |
| Python 客户端 / MCP | ✅ 11/05（客户端 8 条单测；5 个 MCP 工具经 stdio 真实调通） |
| 图与确认卡片 | ✅ 11/05（验收 26/26；headless Chrome 实测：挂起刷新恢复、批准执行、写后读不串台） |
| 评测 | ⬜ 未开始 |
| README / 演示 | ⬜ 未开始 |

硬截止：**2026-11-13 23:59**。一旦有面试，暂停开发去准备面试。

加分项（压测、对账、traceId、注入测试等）都是**可选**，不占 M5 / M6 工期，清单见
`docs/OPTIMIZATION-BACKLOG.md`，只有提前交付才从里面挑。

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

**实施记录（2026-11-05，提前完成主体）**
- Java 前置补齐：`AdminAuditLog` + `AdminAuditService`（best-effort、事务后写）；
  `stock-sync` 的 `increment` 支持 `Idempotency-Key`（锁内二次检查）、`reason`、`replayed`；
  `mvn -pl seckill-service -am test` **102 条**通过；M2 34/34、M3 21/21 回归通过
- Python：`app/config.py`（`llm_api_key` 延迟校验）、`clients/flashmall.py`、`mcp_client.py`、
  `mcp_servers/commerce_server.py`（5 工具）、`graph/`（router→clarify/read/plan_write→confirm→execute→respond）、
  `main.py`（SSE）、`web/index.html`；`pytest` **26 条**、`ruff` 全过
- 端到端：`scripts/acceptance_m4.py` **26/26**，连跑两次一致；覆盖创建/调整幂等回放、
  拒绝无审计、批准审计 +1、跨实例恢复挂起确认
- 环境坑（已修）：checkpointer 要求 Redis **db 0**（RediSearch 不能在非 0 库建索引）；
  `mcp` 2.x 用 `MCPServer` 且字段是 `is_error`；DeepSeek 只支持 `json_mode`；
  Windows 需要 `tzdata` 才能用 `ZoneInfo("Asia/Shanghai")`
- 浏览器：headless Chrome 跑通「发消息出卡片 → 刷新后卡片仍在 → 批准执行 → 写后读列表不串台」
- 踩到并修掉的 bug ①：上一轮写操作的 `result` 残留，导致下一轮查询被渲染成「没有符合条件的活动」；
  现在 router 每轮清空上一轮结果，respond 按 intent 取结果，并加了回归测试（14 条）
- 踩到并修掉的 bug ②（用户实测）：写操作之后问「有哪些进行的活动？」被 router 判成 `unknown`。
  从 checkpoint 取真实历史重放，失败回合 5/5 复现：模型不是分类错，而是**直接回答用户、不输出 JSON**
  ——历史按 `assistant` 角色原样喂，上一轮的活动列表把模型带偏成「接着写」。
  同一批失败回合各跑 5 次（共 15 次）：原样喂 **4/15** 合法、助手回答截断 80 字 **5/15**、
  压成一条 user 消息（「对话历史…／当前用户消息：X」）**15/15**。改法：历史压成一段文本喂，
  `_classify` 加 `include_raw=True` 把模型原话打进日志，另加关键词兜底（模型 unknown 或调用失败时
  用规则再猜一次；写意图仍要过确认卡片）。改后重放 3 个失败回合 **15/15**；
  真实 SSE 端到端复跑「列表 → +1 批准 → 再问列表 → 活动经营数据追问 eventId」全部正常
- 待做：MCP Inspector 手动确认（可选）、M5 评测

### M5（11/12）评测 30 条

**产出** `evals/cases.json` + `evals/run_eval.py` + `evals/report-{split}.md`

| 类别 | 开发集 | 留存集 | 合计 | 判定 |
|---|---|---|---|---|
| 工具选择 | 10 | 2 | 12 | 选对工具名（含 3 条带历史的指代、2 条「不许动手」） |
| 参数抽取 | 9 | 1 | 10 | 价格 / 数量 / 时间区间全部抽对 |
| 该追问不该动手 | 6 | 2 | 8 | 不挂起确认卡片、不调工具、回复点名缺的字段 |

温度 0，跑 3 次统计跨 run 一致率。**失败样例必须逐条分析**，写进 README。

设计要点（2026-11-06 定稿）：
- 用**桩工具箱**，不打真后端：真后端状态会变，同一用例两次结果不同，数字不可复现；
  写操作在确认卡片处挂起，评测不执行任何写工具
- `router_source` 记 `llm / keyword / unknown`，把「模型判对」和「关键词兜底救回来」分开统计
- **25 条开发集 + 5 条留存集**：留存集在提示词定稿前不看、不针对性修，最后一次跑，防止过拟合
- 误动作率（该追问却动手）必须为 0，单独统计

**评测纪律（2026-11-06 定，避免「跑绿为止」）**
1. **留存集最多用两轮**。首轮失败先归类：失败类型在开发集出现过 → 缺的是规则，补确定性代码
   （如 `validate_slots`），失败用例并进开发集回归，不新造留存集；失败类型是全新的 →
   开发集缺的是维度，补一整类用例，再考虑造新一批留存集（且必须换维度，不能只换同义词）。
   第二轮仍挂就停手，把留存集并入开发集，README 如实写「最终数字只代表这批用例的回归状态」。
2. **能写成确定性护栏的，绝不靠提示词**。代码判断不会因为换个说法失效；提示词规则模型
   这次遵守、下次不一定。修复要优先落在 `schemas.py` 这类纯函数里。
3. 5 条留存集的 95% 置信下界只有约 57%（Wilson，5/5），一个用例 ≈ 20 个百分点。
   **不拿留存集当泛化能力的证明**，只当回归风险的探针；报告里给条数（4/5）而不是只给百分比。

**首轮结果（2026-11-06，dev 20 条 × 3 次）**
- 首跑：准确率 96.7%（58/60）、跨 run 一致率 95%、**误动作率 13.3%（2/15）**
- 失败只出现在 `cl-02`「活动 32 加 5 件」：用户没说原因，模型把动作句「加 5 件」当成 `reason`
  填了进去，槽位校验因此放行，直接弹确认卡片。3 次里挂 2 次
- 修法（只改提示词规则 6）：reason 只填用户明说的原因，没说就留空，不许把动作本身当理由
- 修复后重跑：准确率 100%、一致率 100%、误动作率 0%
- **注意**：dev 集一次修改就到 100%，说明用例难度偏低，不能据此宣称泛化能力；
  留存集尚未运行，README 里必须同时给出 dev 与 holdout 两组数字

**补难用例后（2026-11-06，dev 25 条 × 3 次）**
- 新增 5 条：否定句「不要把活动 32 下线」、陈述句「活动 32 好像卖完了」（都不许动手）、
  中文数字「商品七 / 三百块 / 二十件」、一句话两个动作、时间区间为零长度的创建
- 结果：准确率 **96.0%（72/75）**、跨 run 一致率 100%、字段级准确率 100%、
  **误动作率 16.7%（3/18）**、P50 861 ms
- 唯一失败 `cl-06`（3/3 稳定复现）：开始时间与结束时间相同，槽位齐全 → 直接弹确认卡片，
  而 Java 必然拒绝「结束时间必须晚于开始时间」。用户白批一次，卡片看起来还是合法的。
  **已修（11-06）**：`schemas.validate_slots()` 做跨字段校验（价格 > 0、库存 > 0、结束晚于开始、
  调整量 ≠ 0），不合法与缺槽位一样走 clarify，卡片不再出现；新增单测 8 条（共 36 条）
- 修复后 dev 复跑：**准确率 100%（75/75）、一致率 100%、字段级 100%、误动作率 0%**、P50 811 ms
- `sl-09`（一句话两个动作）只判第一个动作：第二个动作被静默丢弃，属已知缺陷，不是该用例的判定点

**留存集结果（2026-11-06，5 条 × 3 次，只跑了这一轮）**
- 首轮：**12/15**（工具选择 9/9、参数抽取 3/3、该追问 3/6），跨 run 一致率 100%
- 唯一失败 `hc-02`「第一个卖得怎么样」：期望是追问活动 id，实际把上一轮列表里的
  「1. 活动 32」**正确解析成 `eventId=32` 去查**。这是**用例期望写错（oracle 错误），
  不是系统错误**——系统行为比用例写得好。dev 集里 `sl-09` 也栽过同一种错
- 同时修了口径：误动作率原来把「调只读工具」也算进去（hc-02 因此显示 50%），
  现在只统计「挂起确认卡片 / 调用写工具」
- 按正确期望重跑：**15/15**、一致率 100%、误动作率 0%、P50 858 ms
- 本轮**没有改提示词、没有改代码，只改期望**。但留存集已被看过，不能再当泛化证明：
  README 必须同时写「首轮 12/15（含 1 条期望错误）」和「修正后 15/15」，
  只写 15/15 是不诚实的

**已知缺口**：用例里的时间全部写绝对时间（如「2026-12-01 上午十点」），
相对时间（「明天下午三点」「下周三」）没有进评测——它依赖运行时的当前时间，无法复现。
相对时间目前只在人工验收里覆盖，README 要如实写。

### M6（11/13）README + 演示

**顺序（2026-11-06 定）：README → A5 前端三件套 → 录屏**

- 一页 README：架构图、真实数字、「为什么不用 Spring AI」「为什么不做 RAG / 多 Agent」
- README 必须如实写的四条局限：
  ① keyword 兜底在 90 次评测调用里从未触发，等于未验证；
  ② 相对时间（「明天下午三点」）没进评测，只人工验过；
  ③ 留存集 5 条已被看过并改过一次期望，只能当探针，不能当泛化证明；
  ④ 一句话两个动作只执行第一个，第二个被静默丢弃
- **A5 前端三件套**（0.5d，细则见 `docs/OPTIMIZATION-BACKLOG.md` §1.1）：
  结果卡片化、空状态 3 个可点示例、待确认徽标。**不改后端接口、不碰 SSE 解析**
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
