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

### 0.4 新增数据库对象（迁移文件 `mysql/migrations/2026-11-05-admin.sql`）

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

现有 7 张表**不做结构变更**。活动状态由 `start_time` / `end_time` 推导。

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
| `stock <= 0` 或 `stock > product.stock` | 秒杀库存必须在 1 到商品库存之间 |
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

### 1.4 `POST /api/admin/seckill/events/{id}/stock-sync` — 预热 / 增量调整

```json
{"mode": "reset"}
{"mode": "increment", "delta": 10}
```

- `reset`：要求 `status=pending`。写 `seckill:stock:{productId}` = `event.stock`，
  写 `seckill:event:{productId}` = `{"eventId":9,"startTs":...,"endTs":...}`
- `increment`：要求 `status=running`。`event.stock += delta` 与 Redis `INCRBY` **同值同锁**执行；
  结果不得 `< 0`；需加分布式锁（复用 `Redisson` 或 `CacheLockUtil`），防止并发调整与秒杀扣减交错
- `data`：`{"dbStock":50,"redisStock":50,"mode":"reset"}`

只读 Key 约定（`RedisKeyUtil` 里已有 `SEC KILL_EVENT_KEY` 常量但从未使用，本次启用）：

| Key | 内容 | 写入方 |
|---|---|---|
| `seckill:stock:{productId}` | 剩余库存字符串 | stock-sync |
| `seckill:event:{productId}` | `{"eventId","startTs","endTs"}` | stock-sync |
| `seckill:user:{productId}:{userId}` | 去重标记，TTL 3600s | Lua |

> `RedisKeyUtil.getSeckillEventKey(Long)` 当前形参按「活动 id」实现，与秒杀侧只有 `productId` 的事实不符。
> 实现时改为按 `productId` 取名，或新增 `buildSeckillEventKey(productId)`，**不要两套并存**。

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

- `orderCount`：`order.product_id=?` 且 `create_time` 落在活动区间
- `paidCount`：上述订单中 `status ∈ (1,2,3)`
- `gmv`：上述已支付订单的 `total_amount` 求和
- `stockConsumed = stockTotal - redisStock`；`ordersOutstanding`：`status=0` 的订单数
- Redis 不可达时 `redisStock = null`，**不要用 0 冒充**

### 1.6 秒杀链路唯一改动

`SeckillServiceImpl.seckill()` 开头，在执行 Lua 之前：

1. 读 `seckill:event:{productId}`，不存在 → `throw RuntimeException("活动不存在或未预热")`
2. `now` 不在 `[startTs, endTs]` → `"活动未开始"` / `"活动已结束"`
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
- **判定**：`redisStock - dbStock` 不等于 `ordersOutstanding` 时标记为可疑，输出差值
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
- **确认卡片**：展示商品名、秒杀价（并标注原价）、数量、起止时间、影响范围（会写入 Redis 并可被抢购）
- **幂等键**：`{thread_id}:{tool_call_id}`
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

- **映射**：`POST /api/admin/seckill/events/{id}/stock-sync {"mode":"increment","delta":n}`
- **确认卡片**：展示调整前后库存、原因、影响（可能让更多人抢到或提前售罄）
- **幂等键**：`{thread_id}:{tool_call_id}`
- **写操作**：是

---

## 3. 图结构

```
START → router(结构化输出: intent + slots)
      ├─ intent=query_events    → query_seckill_events → respond
      ├─ intent=query_stats     → get_seckill_stats    → respond
      ├─ intent=diagnose        → diagnose_stock       → respond
      ├─ intent=create_event    → [slot 不全 → clarify] → create_seckill_event → interrupt → execute → respond
      ├─ intent=adjust_stock    → [slot 不全 → clarify] → adjust_seckill_stock → interrupt → execute → respond
      └─ intent=unknown/out_of_scope → respond（说明能做什么）
```

- **router 一次调用同时出 intent 和 slots**，不拆成两次 LLM 调用
- Checkpointer：Redis，`thread_id` 由前端会话 id 提供；`interrupt` 挂起后进程重启仍可恢复
- 工具返回统一走 **裁剪函数**：列表最多保留 10 条、长文本截断到 500 字
- 节点内部异常一律转成 `respond`，不把堆栈丢给模型
