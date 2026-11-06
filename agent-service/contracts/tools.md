# 工具契约

> 先写契约，再实现两端。Java 接口与 Python 工具都以本文件为准。
> 契约改动必须同时改 Java 与 Python，并在同一提交里完成。
> 范围依据：`docs/AGENT-SCOPE.md`。

## 0. 公共约定

### 0.1 调用链

```
Agent → MCP 工具 → httpx → 网关 :8888 → seckill-service :8080
```

**不直连 8080**（会绕过网关，`X-User-Id` 可伪造）。Agent 持有管理员账号，启动时登录取 JWT，过期自动重登一次。

### 0.2 响应封套（来自 `Result<T>`）

| 情况 | HTTP | body | Python 映射 |
|---|---|---|---|
| 成功 | 200 | `{"code":200,"message":"操作成功","data":{...}}` | `{"ok":true,"data":...}` |
| 业务失败 | 200 | `{"code":500,"message":"库存不足"}` | `{"ok":false,"error":{"code":"BIZ","message":"库存不足"}}` |
| 鉴权失败 | **401** | `{"code":401,"message":"Token 已过期"}` | 重登一次；仍失败 → `AUTH` |

**必须同时判断 HTTP 状态码和 body.code。** 只看 HTTP 会漏掉全部业务错误。

### 0.3 写操作三件套（缺一不可）

1. **人工确认**：LangGraph `interrupt()` 挂起，前端展示确认卡片，`Command(resume=...)` 恢复
2. **幂等键**：`Idempotency-Key` 由 Python 生成（`f"{thread_id}:{tool_call_id}"`），**不由模型提供**
3. **审计**：落 `admin_audit_log`，记录谁、何时、什么参数、结果、是否被批准
   - 审计行由 **Java 在业务执行成功时写入**，`approved=1`；Python 不直连 MySQL
   - 用户拒绝确认 → 不产生业务请求，也不产生审计行（验收看「审计表无新增」）；
     Python 侧只写一条结构化日志 `audit.rejected`（thread_id、tool_name、参数摘要）
   - 幂等回放不重复写审计

### 0.4 新增数据库对象（`mysql/init/03-admin.sql`）

`mysql/init/` 挂载到容器的 `/docker-entrypoint-initdb.d`，**首次启动自动执行**。
脚本全部用 `CREATE TABLE IF NOT EXISTS`，因此对已有数据的库可以重复执行：

```bash
docker exec -i flashmall-mysql mysql -uroot -proot123 flashmall < mysql/init/03-admin.sql
```

现有 7 张表**不改结构**，活动状态由 `start_time` / `end_time` 推导。

```sql
CREATE TABLE IF NOT EXISTS admin_idempotency (
  idempotency_key VARCHAR(128) PRIMARY KEY,
  response_json   TEXT NOT NULL,
  created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS admin_audit_log (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  idempotency_key VARCHAR(128),
  tool_name      VARCHAR(64)  NOT NULL,
  operator_id    BIGINT       NOT NULL,
  params_json    TEXT,
  result_json    TEXT,
  approved       TINYINT      NOT NULL DEFAULT 0,
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_created (created_at),
  KEY idx_tool (tool_name)
);
```

活动状态由 `start_time` / `end_time` 推导，不新增 `status` 列。

---

## 1. Java 接口

### 1.1 `POST /api/admin/seckill/events` — 创建活动

Header：`Authorization: Bearer <jwt>`、`Idempotency-Key: <thread_id:tool_call_id>`

```json
{"productId": 7, "seckillPrice": 299.90, "stock": 50,
 "startTime": "2026-11-11T10:00:00", "endTime": "2026-11-11T12:00:00"}
```

成功 `data`：`{"id":9,"productId":7,"seckillPrice":299.90,"stock":50,"startTime":"...","endTime":"...","status":"pending","replayed":false}`

校验（全部返回 `code=500` + 中文 message）：

| 条件 | message |
|---|---|
| 商品不存在 | 商品不存在 |
| `seckillPrice >= product.price` | 秒杀价必须低于商品原价 |
| `stock <= 0` 或 `stock > product_stock.stock` | 秒杀库存必须在 1 到商品库存之间 |
| `startTime >= endTime` | 开始时间必须早于结束时间 |
| `endTime <= now` | 结束时间必须晚于当前时间 |
| 同 `productId` 已有 `endTime > now` 的活动 | 该商品已存在未结束的活动（id=9） |

幂等：同 key 重复请求**不报错**，直接回放首次响应并把 `replayed` 置 `true`。

### 1.2 `GET /api/admin/seckill/events` — 列表

Query：`status`（`all|pending|running|ended`，默认 `all`）、`productId?`、`page=1`、`size=10`（上限 20）

`data`：`{"records":[{...}],"total":8,"pages":1}`，记录含 `id, productId, productName, seckillPrice, stock, startTime, endTime, status`

状态推导：`now < startTime` → `pending`；`startTime <= now <= endTime` → `running`；否则 `ended`。

### 1.3 `PUT /api/admin/seckill/events/{id}` — 修改 / 下线

```json
{"seckillPrice": 199.90, "stock": 80, "startTime": "...", "endTime": "..."}
```

- 字段均可选，至少传一个
- `status=running` 或 `ended` 时**只允许**改 `endTime`（下线）。下线用法：`{"endTime":"<当前时间>"}`
- 已下线（`endTime <= now`）的活动不可再改
- 修改成功后，如果该商品**已经预热**（`seckill:event:{productId}` 存在），同步刷新窗口；
  未开始的活动改库存时同时刷新 Redis 库存；刷新失败则删除窗口 key（fail-closed，宁可「未预热」也不按旧窗口放行）

### 1.4 `POST /api/admin/seckill/events/{id}/stock-sync` — 预热 / 增量调整

Header（仅 `increment` 使用）：`Idempotency-Key: <thread_id:tool_call_id>`、`Authorization`、`X-User-Id`

```json
{"mode": "reset"}
{"mode": "increment", "delta": 10, "reason": "活动热度高，临时加库存"}
```

`reason` 可选，写进审计 `params_json`。

- `reset`：要求 `status=pending`。**先写库存、再写窗口**（顺序不可反，保证窗口存在时库存一定就位）：
  - `seckill:stock:{productId}` = `event.stock`
  - `seckill:event:{productId}` = `{"eventId":9,"startTs":<epoch毫秒>,"endTs":<epoch毫秒>}`
  - 时间按 `Asia/Shanghai` 把 DB 的 `LocalDateTime` 转成 epoch 毫秒
  - 写 Redis 前先读 `product_stock.stock` 校验 `event.stock <= 仓库剩余`，不足则拒绝预热
    （否则 Redis 可卖数会多于仓库，用户抢到后会在消费者侧失败；消费者无死信）
- `increment`：要求 `status=running`，`delta != 0`。加分布式锁后 Redis `INCRBY` 与
  DB `stock = stock + delta` 同值同增；任一结果会让库存 `< 0` 时整体拒绝并补偿 Redis
- `data`：`{"dbStock":50,"redisStock":50,"mode":"reset","replayed":false}`
  - `dbStock` = `seckill_event.stock`（活动**总配额**，成交后不变）
  - `redisStock` = Redis 当前**剩余可抢**；reset 时两者相等，成交或 increment 后可以不等
  - `replayed` = 命中同一幂等键的回放时为 `true`（库存不会被再次调整）
- 错误（`code=500` + 中文 message）：
  `活动不存在`、`只有未开始的活动可以预热`、`仓库库存不足，无法预热：活动库存 X，仓库剩余 Y`、
  `只有进行中的活动可以调整库存`、`库存调整量不能为 0`、`调整后库存不能为负数`、
  `活动未预热，请先预热`、`库存调整正忙，请稍后重试`、`库存同步失败，请稍后重试`

只读 Key 约定（`RedisKeyUtil` 里已有的 `SECKILL_EVENT_KEY` 常量从未使用，本次启用；
旧 `getSeckillEventKey(Long seckillEventId)` 按活动 id 取 key 的语义删除，只保留按 `productId` 的一套）：

| Key | 内容 | 写入方 |
|---|---|---|
| `seckill:stock:{productId}` | 剩余库存字符串 | stock-sync、Lua、预热 |
| `seckill:event:{productId}` | `{"eventId","startTs","endTs"}`，epoch 毫秒 | stock-sync、接口 3 刷新 |
| `seckill:user:{productId}:{userId}` | 去重标记，TTL 3600s | Lua |

> 窗口 key **不设 TTL**：活动结束后仍要能返回「活动已结束」，TTL 到期会变成「未预热」，消息不准。
> 清理留给后续版本。

### 1.5 `GET /api/admin/seckill/events/{id}/stats` — 统计快照

```json
{
  "event": {"id":9,"productId":7,"status":"running","stock":50,"startTime":"...","endTime":"..."},
  "stats": {"orderCount":12,"paidCount":9,"gmv":2699.10,"payRate":0.75,
            "stockTotal":50,"stockConsumed":12,"consumeRate":0.24},
  "stock": {"dbStock":38,"redisStock":38,"ordersOutstanding":0}
}
```

口径：

- `orderCount`：`order.product_id=?` 且 `create_time` 落在 `[start_time, end_time]`
- `paidCount`：上述订单中 `status ∈ (1,2,3)`（已支付 / 已发货 / 已完成）
- `gmv`：`paidCount × event.seckill_price`，保留 2 位小数。
  **不要 `SUM(order.total_amount)`**：消费者落库写的是 `product.price`（原价），不是秒杀成交价
- `payRate = paidCount / orderCount`；`orderCount=0` 时为 `0`
- `stockTotal = event.stock`（活动总配额，`increment` 后会变）
- `stockConsumed = stockTotal - redisStock`；`consumeRate = stockConsumed / stockTotal`
  （两者是**净消耗**：取消/超时回滚会让它变小，所以可能小于 `orderCount`）
- `stock.dbStock = product_stock.stock`（仓库剩余）。注意与 stock-sync 的
  `dbStock = event.stock`（活动总配额）同名不同义；统计这里取它是为了能诊断 Redis 与仓库的漂移
- `ordersOutstanding`：上述订单中 `status=0` 的订单数
- Redis 不可达时 `redisStock = null`，**不要用 0 冒充**；此时 `stockConsumed` 与
  `consumeRate` 也返回 `null`（库存未知，比率同样未知）
- `stockTotal=0` 时 `consumeRate = null`
- 口径边界：消费者积压导致订单在 `end_time` 之后才落库的，不计入本场统计

### 1.6 秒杀链路唯一改动

`SeckillServiceImpl.seckill()` 开头，在执行 Lua 之前：

1. 读一次 `seckill:event:{productId}`，不存在或 JSON 解析失败 → `throw RuntimeException("活动不存在或未预热")`
2. `System.currentTimeMillis()` 不在 `[startTs, endTs]` → `"活动未开始"` / `"活动已结束"`（epoch 毫秒比较）
3. 之后原样走 `seckillRedisScript.executeSeckill(...)`

**不许改** `seckill.lua`、`SeckillConsumer`、`StockRollbackConsumer`、`ProductStockMapper.decrementStock`。

---

## 2. MCP 工具

返回统一为 `{"ok":true,"data":...}` 或 `{"ok":false,"error":{"code":"...","message":"..."}}`，
`message` 写给模型看，必须是完整中文句子，不要暴露堆栈。

### 2.1 `query_seckill_events`（读）

- **description**：查询秒杀活动列表。用户问「有哪些活动 / 某个商品的活动状态 / 活动什么时候开始」时使用。
  **不要**用它做销量或金额统计（那是 `get_seckill_stats`），**不要**用它判断库存是否一致（那是 `diagnose_stock`）。
- **参数**

```json
{"type":"object","properties":{
  "status":{"type":"string","enum":["all","pending","running","ended"],"default":"all"},
  "productId":{"type":"integer","minimum":1},
  "page":{"type":"integer","minimum":1,"default":1}
},"required":[]}
```

- **映射**：`GET /api/admin/seckill/events`
- **返回**：活动数组 + 总数；空结果返回 `"没有符合条件的活动"` 而非空数组
- **写操作**：否

### 2.2 `get_seckill_stats`（读）

- **description**：查询某个活动的经营数据（订单数、支付率、GMV、库存消耗率）与当前库存快照。
  用户问「卖得怎么样 / 成交多少 / 还剩多少」时使用。**必须**有明确的活动 id；用户没说要查哪个活动时先追问。
- **参数**

```json
{"type":"object","properties":{
  "eventId":{"type":"integer","minimum":1}
},"required":["eventId"]}
```

- **映射**：`GET /api/admin/seckill/events/{id}/stats`
- **返回**：数值直接给，附一行中文结论（如「已售 12/50，支付率 75%」）
- **写操作**：否

### 2.3 `diagnose_stock`（读）

- **description**：检查库存一致性——对比数据库活动库存、Redis 实际库存、未支付订单数，
  找出可能的漂移。用户问「库存对不对 / 有没有超卖 / 为什么抢不到」时使用。
  传 `eventId` 只查一场，不传则查全部进行中的活动。
- **参数**

```json
{"type":"object","properties":{
  "eventId":{"type":"integer","minimum":1}
},"required":[]}
```

- **映射**：`GET /api/admin/seckill/events?status=running` + 逐个 `GET .../{id}/stats`
- **判定**（任一成立即标记可疑，并输出具体差值）：
  - `redisStock` 为 `null`（Redis 不可读，无法确认）
  - `redisStock` 不在 `[0, stockTotal]` 内（预热/回滚把数量加坏）
  - `stockConsumed > orderCount`（Redis 已扣但订单没落库，可能是消费者丢消息）
  - `stock.dbStock - redisStock` 只作为参考差值展示，**不参与判定**：
    活动配额与仓库余量本来就可以不相等（商品 7 是 30/50），且消费者会同步扣两边，差值无固定口径
- **写操作**：否（**只诊断，不修复**；修复走 `adjust_seckill_stock`）

### 2.4 `create_seckill_event`（写）

- **description**：创建秒杀活动并预热库存。用户给出商品、价格、数量、时间区间时使用。
  **缺任何一个必填参数都必须先追问，不要猜。** 时间按用户所在时区（UTC+8）理解。
- **参数**

```json
{"type":"object","properties":{
  "productId":{"type":"integer","minimum":1},
  "seckillPrice":{"type":"number","exclusiveMinimum":0},
  "stock":{"type":"integer","minimum":1},
  "startTime":{"type":"string","description":"ISO8601，如 2026-11-11T10:00:00"},
  "endTime":{"type":"string","description":"ISO8601"}
},"required":["productId","seckillPrice","stock","startTime","endTime"]}
```

- **映射**：`POST /api/admin/seckill/events` → 成功后自动 `POST .../{id}/stock-sync {"mode":"reset"}`
  - 两步不是原子的：创建成功后 reset 可能因「仓库库存不足」失败。此时活动已建但未预热，
    工具必须如实返回两部分结果（活动 id + 预热失败原因），不能报整体成功
- **确认卡片**：展示商品名、秒杀价（并标注原价）、数量、起止时间、影响范围（会写入 Redis 并可被抢购）
- **幂等键**：`{thread_id}:{tool_call_id}`
- **实现细节**：MCP 工具签名额外带 `idempotencyKey` 参数（默认空、为空直接拒绝），
  由图的 `plan_write` 节点注入；模型不接触工具 schema，不会自行填写
- **审计**：落库，`approved` 记录用户是否点了确认
- **写操作**：是

### 2.5 `adjust_seckill_stock`（写）

- **description**：调整**进行中**活动的库存。用户说「再加 20 件 / 减少 10 件 / 库存改了」时使用。
  必须先运行 `diagnose_stock` 或 `get_seckill_stats` 确认当前库存，并向用户复述调整前后数字。
- **参数**

```json
{"type":"object","properties":{
  "eventId":{"type":"integer","minimum":1},
  "delta":{"type":"integer","description":"正数增加，负数减少，不能为 0"},
  "reason":{"type":"string","minLength":1,"description":"调整原因，写进审计日志"}
},"required":["eventId","delta","reason"]}
```

- **映射**：`POST /api/admin/seckill/events/{id}/stock-sync {"mode":"increment","delta":n,"reason":"..."}`，
  header 带 `Idempotency-Key: {thread_id}:{tool_call_id}`；同键重复调用只加一次
- **确认卡片**：展示调整前后库存、原因、影响（可能让更多人抢到或提前售罄）
- **幂等键**：`{thread_id}:{tool_call_id}`；同 §2.4，工具签名带 `idempotencyKey`，由 `plan_write` 注入
- **写操作**：是

---

## 3. 图结构

```
START → router(结构化输出: intent + slots)
      ├─ intent=query_events    → query_seckill_events → respond
      ├─ intent=query_stats     → get_seckill_stats    → respond
      ├─ intent=diagnose        → diagnose_stock       → respond
      ├─ intent=create_event    → [slot 不全 → clarify] → plan_write → confirm(interrupt) → execute → respond
      ├─ intent=adjust_stock    → [slot 不全 → clarify] → plan_write → confirm(interrupt) → execute → respond
      └─ intent=unknown/out_of_scope → respond（说明能做什么）
```

- **router 一次调用同时出 intent 和 slots**，不拆成两次 LLM 调用
- **幂等键只在 `plan_write` 生成**：`{thread_id}:{tool_call_id}`，先写入 checkpoint 再 `interrupt()`；
  恢复时 `interrupt()` 所在节点会从头重跑，键必须在它之前的节点固定，否则重启/重试会换键导致重复执行
- 恢复统一传 `Command(resume={"approved": true|false, "feedback": "..."})`，不接受裸 bool
- 拒绝（`approved=false`）：`execute` 直接返回，不调工具、不写业务表；只记结构化日志 `audit.rejected`
- Checkpointer：Redis，`thread_id` 由前端会话 id 提供；`interrupt` 挂起后进程重启仍可恢复
- 工具返回统一走 **裁剪函数**：列表最多保留 10 条、长文本截断到 500 字
- 节点内部异常一律转成 `respond`，不把堆栈丢给模型
