# 已知缺陷与修复计划

> 记录于 2026-11-10。**这份文档记录的是「未修」的东西，不是已完成项。**
> 修完一项：把状态改成 ✅，并在「验收证据」里写命令与真实输出。
> 决定不修的：必须写进 §7，留理由。**不允许默默消失。**
>
> 相关文档：加分项见 `OPTIMIZATION-BACKLOG.md`；这两份不是一回事
> ——那边是「还能加什么」，这里是「已经坏在哪」。

## 0. 优先级定义

| 级别 | 判据 |
|---|---|
| **P0** | 用户能直接感知，且数据不会自愈（订单不出现、钱和货对不上） |
| **P1** | 数据会偏差，但能通过对账发现、人工补回 |
| **P2** | 极端场景、单机限制或运维项，正常演示不会触发 |

## 0.1 硬约束（动手前必须确认）

- 受保护文件：`seckill.lua`、`SeckillConsumer`、`StockRollbackConsumer`、`decrementStock`。
  改其中任何一个，都需要**显式解除约束**，不能顺手改。
- 每次改完必须跑 `python scripts/acceptance_m2.py`，要求 **34/34**。
- Agent 不进秒杀主链路；`agent-service` 不直连 MySQL / Redis。

---

## 1. P0-1 秒杀成功但订单永远不出现

**状态**：✅ 已修（2026-11-12，端到端验收 10/10 通过）　**记录日期**：2026-11-10　**用户判定**：必修

### 现象（用户视角）

秒杀接口返回 `{"code":200,"message":"秒杀成功，订单处理中..."}`，
但「我的订单」里永远没有这一条。用户无法判断是自己没抢到，还是系统坏了。

### 触发链（代码级）

1. `SeckillConsumer.handleSeckillOrder` 里 `processBatchOrders` 抛异常
   （数据库连接超时、`uk_order_no` 冲突、`product_name` 超长等）
2. `catch` 分支把**整批**的 Redis 库存回补、删用户标记
   （`SeckillConsumer.java:84-99`）
3. `channel.basicNack(deliveryTag, true, false)` —— `requeue=false`
4. 队列 `seckill.order.queue` **没有配置死信交换机**
5. 消息被 Broker 直接丢弃

### 根因

「消费失败」和「消息丢弃」之间没有缓冲层。失败一次 = 永久丢失。

### 影响

- 50 条一批的批次，一次异常丢 50 个用户的订单
- Redis 库存被回补，DB 库存（如果事务已提交）仍被扣 → 两边对不上
- 用户没有任何补偿入口

### 修复方案（按顺序）

| 步骤 | 改什么 | 解决什么 |
|---|---|---|
| 1 | `RabbitMQConfig` 声明死信交换机 + `seckill.order.dlq`，给队列加 `x-dead-letter-exchange` | 消息不再静默消失 |
| 2 | `catch` 只回补**当前失败的商品组**，不回补整批 | 已成功的组不再被反向污染 |
| 3 | `processBatchOrders` 拆到独立 Bean，让 `@Transactional` 真正生效 | 扣库存与插订单原子，失败一起回滚 |
| 4 | 消费幂等：`INSERT ... ON DUPLICATE KEY UPDATE` 或先查后插 | 重复消息不再拖垮整批 |
| 5 | DLQ 重放脚本（读 DLQ → 重新投递 → 校验订单数） | 已丢的消息能人工补回 |

**注意**：步骤 1 会改队列参数。已存在的 durable 队列参数不可变，
开发环境要先 `rabbitmqctl delete_queue` 重建，否则启动报 `PRECONDITION_FAILED`。

### 复现方式

```powershell
# 1. 先制造一个已存在的 order_no
docker exec flashmall-mysql mysql -uroot -proot123 -e 'SELECT order_no FROM flashmall.`order` LIMIT 1;'

# 2. 用 RabbitMQ 管理台（localhost:15672，admin/123456）往 seckill.order.queue
#    手动发一条消息，orderNo 填上面查到的值：
#    {"userId":1,"productId":7,"orderNo":"<已存在的order_no>"}

# 3. 观察：消费者报 uk_order_no 冲突 → 消息消失 → 订单数不变
```

### 验收标准

- 人为制造一次 `insertBatch` 失败后：消息出现在 `seckill.order.dlq`，**不在原队列**
- `order` 表没有新增、`product_stock` 库存不变、Redis 库存不变（三者一致）
- 重放 DLQ 后订单正常生成，且只有一条
- `acceptance_m2.py` 仍为 34/34

### 验收证据（2026-11-12，真实输出）

改动落地：`RabbitMQConfig` 声明 `seckill.dlx` / `seckill.order.dlq`；`SeckillOrderBatchService`
承接原来同类自调用的 `processBatchOrders`；`SeckillConsumer` 改为按商品分组处理、只回补失败组；
`OrderMapper.selectExistingOrderNos` + `DlqReplayService` + `AdminDlqController` 新增。

队列参数已生效（`rabbitmqctl list_queues name messages arguments`）：

```
seckill.order.queue  0  [{"x-dead-letter-exchange","seckill.dlx"},{"x-dead-letter-routing-key","seckill.order.dead"}]
seckill.order.dlq    0  []
```

`python scripts/acceptance_dlq.py`：

```
[1] 幂等：orderNo=DLQTEST-... 投两次
  [PASS] 首次投递落库 orderCount=1
  [PASS] 首次投递扣一次库存 stock=49
  [PASS] 重复投递不重复落库 orderCount=1
  [PASS] 重复投递不重复扣库存 stock=49
  [PASS] 重复消息不进死信 dlq=0
[2] 死信：库存置 0 后投递新消息
  [PASS] 失败消息进入死信队列 dlq=1
  [PASS] 失败消息没有落库
[3] 重放：恢复库存后调用 /dlq/replay
  [PASS] 重放接口返回 {'replayed': 1, 'failed': 0, 'limit': 10}
  [PASS] 重放后订单补上
  [PASS] 重放后死信清空 dlq=0
== 结果：PASS 10 / FAIL 0 ==
```

回归：`acceptance_m2.py` 34/34、`acceptance_m3.py` 21/21、`mvn -pl seckill-service -am test` 111 条全绿。

---

## 2. P0-2 抢完立刻支付提示「订单不存在」

**状态**：✅ 后端已修（2026-11-12），前端轮询未做　**记录日期**：2026-11-10　**用户判定**：必修

### 现象（用户视角）

刚看到「秒杀成功」，马上点支付，弹出
`{"code":500,"message":"订单不存在，请稍后重试（订单异步处理中）"}`。
用户会以为白抢了。

### 根因

同步链路只保证「消息进队列」，订单在消费者里才落库
（`PayController.java:40-42`）。这个错误信息本身承认了原因，但对用户没有意义。

### 修复方案（推荐组合）

1. **后端给明确语义**：`PayController` 查不到订单时，返回一个专用 code
   （例如 `code=202` + `message="订单处理中，请稍后重试"`），不要和「订单真的不存在」混在一起
2. **前端轮询**：拿到 orderNo 后展示「订单生成中」，按 200ms 间隔轮询
   `GET /api/order/no/{orderNo}`，查到 `status=0` 再渲染支付按钮
3. **超时兜底**：轮询 10 秒仍未出现，提示「订单生成异常，请联系客服」并记录 orderNo

**不做**：在支付接口里 `sleep` 等待 —— 占 Tomcat 线程，活动期间会拖垮整个服务。

### 复现方式

```powershell
# 抢购后立刻（<200ms）调用支付
curl.exe -s -X POST "http://localhost:8888/api/pay/mock?orderNo=<刚返回的orderNo>" -H "Authorization: Bearer $TOKEN"
```

### 验收标准

- 抢购后立刻支付：返回「订单处理中」，**不是**「订单不存在」
- 前端轮询在 1 秒内渲染出支付按钮
- 订单真的不存在时（orderNo 写错），仍返回「订单不存在」

### 实现（2026-11-12）

- `PayController.mockPay` 查不到订单时，按订单号前 14 位（`yyyyMMddHHmmss`）
  判断是否「刚下单」：10 分钟内 → `code=202 + "订单处理中，请稍后重试"`，
  否则仍返回「订单不存在」
- 订单号不是本系统格式（例如普通订单的 `ORD` 前缀）时不做猜测，直接「订单不存在」
- **注意**：只做到「后端给可区分的语义」。前端拿到 202 后轮询渲染支付按钮属于 A5 前端三件套，
  本次未验证。
- 实现时踩到的坑：用前 17 位（`yyyyMMddHHmmssSSS`）解析，**JDK 8 会直接抛
  `DateTimeParseException`**（同样的串在 JDK 17 上能解析）。容器里跑的是 JRE 8，
  所以本地单测全绿、接口仍返回 500。改用 14 位秒级时间后正常。

### 验收证据（2026-11-12）

`acceptance_stage3.py` §7：

```
[PASS] 新生成的订单号 → code=202
[PASS] 不认识的订单号 → code=500 订单不存在
```

---

## 3. P1 级

### P1-1 乐观锁已提交、插单失败 → 库存永久少卖

**状态**：✅ 已修（2026-11-12，含失败反证）

- `decrementStock` 成功（autocommit 已提交）后 `insertBatch` 抛异常
- DB 库存永久少 N、没有任何订单；catch 又把 Redis 加回去 → Redis 比 DB 多 N
- **修复**：已随 P0-1 步骤 3 完成 —— `processBatchOrders` 移到 `SeckillOrderBatchService`，
  `@Transactional` 真的生效，扣库存与插单同成同败
- **反向验收证据**：`acceptance_stage3.py` §9 —— 先把 Redis 手工 −1（模拟 Lua 预减），
  再投一条 `order_no` 长 200 字符的毒消息：
  仓库库存不变（事务把扣减回滚了）、Redis 被补回预减前的值、毒消息进 `seckill.order.dlq`，
  跑完用管理 API 清空死信队列

### P1-2 异常时回补整批 Redis，包含已成功的组

**状态**：✅ 已修（2026-11-12）

- ~~`SeckillConsumer.java:84-99` 的 for 循环遍历的是整批 50 条~~
- 现在 `handleSeckillOrder` 先按 `productId` 分组，失败只回补当前组
- **验收证据**：`SeckillConsumerTest.testPartialFailure`
  —— 7 失败、8 成功时，只调用 `rollbackStock(7, *)`，对 8 的调用次数为 0

### P1-3 消费无幂等，一条重复消息拖垮整批

**状态**：✅ 已修（2026-11-12）

- 插入前先 `selectExistingOrderNos` 批量查重，重复的 `order_no` 整条跳过
- **验收证据**：`acceptance_dlq.py` [1] —— 同一 `orderNo` 投两次，
  订单数恒为 1、仓库库存只扣一次（50→49→49）、不进死信

### P1-4 支付与超时取消赛跑

**状态**：✅ 已修（2026-11-12）

- `PayController.mockPay` 和 `OrderTimeoutConsumer` 都是「先 select 判断，再 update」
- `order` 表**没有 version 字段** → 两边都读到「待支付」，后写覆盖先写
- 结果：用户付了钱，订单可能被标成「已超时」，库存也退回去了
- **修复**：状态 CAS —— `OrderMapper.casPay` / `casTerminate`，都是
  `UPDATE \`order\` SET status=? WHERE id=? AND status=0`；
  `cancelOrder` 也改成 CAS 并去掉 `@Transactional`（单条语句，事务只会让
  「状态已改、消息未发」回滚成死循环）
- **重投语义补齐**：CAS 返回 0 时不能直接 ack。若订单已是 TIMEOUT/CANCELLED，
  说明上一轮可能死在「状态改成功、回滚消息没发出去」之间，必须补发回滚消息
  （消费端按 orderId 幂等）
- **验收**：`acceptance_stage3.py` §8 并发跑支付与取消 3 轮，终态只可能是 1 或 4，
  且第 9 节对账 `overall=OK` 证明库存与终态一致

### P1-7 库存回滚不幂等（重复投递多退一次）

**状态**：✅ 已修（2026-11-12）

- 原实现：`StockRollbackConsumer` 无条件 `stock = stock + 1`，
  同一条回滚消息投两次 → 仓库多退一件 → 下一场活动预热校验（配额 ≤ 仓库剩余）
  照样通过 → 真超卖
- 幂等键**必须是 orderId**，不能用 `{productId,userId}`：回滚会删掉 Redis 用户标记，
  同一用户取消后可以再抢一次、再取消一次，两条消息参数完全一样
- 修复：
  - 新增 `stock_rollback_log`（主键 `order_id`），台账写入与 `product_stock + 1`
    在同一个本地事务里
  - 顺序固定 **先 DB 后 Redis**：DB 成功、Redis 失败只会「少卖」（对账可发现）；
    反过来会「多卖」，用户下单后扣不动库存
  - 回滚消息体加 `orderId` + `reason`（`CANCEL` / `TIMEOUT`），台职能看出是谁退的
  - 老格式消息（缺 `orderId`）无法去重 → 打 ERROR 后 ack，宁可少退不可多退
- **验收证据**：`acceptance_stage3.py` §2 —— 同一 `orderId` 投两次：
  台账 1 行、DB 只 +1、Redis 只 +1；§4 —— 取消接口连点两次仍只退 1 件

### P1-5 延迟取消消息可能静默丢失

**状态**：⬜ 未修

- `convertAndSend("order.delay.exchange", ...)` 不保证交换机存在
- order-service 没起来 / 启动失败 → Broker 静默丢弃，生产端不报错
- 结果：订单永远不会超时取消，库存永远不回补
- **修复**：开启 `mandatory` + `ReturnsCallback`，发送失败落本地表重试
- **验收**：停掉 order-service 后下单，生产端能收到 return 回调并记录

---

### P1-6 库存漂移无人发现（没有自动对账）

**状态**：✅ 已修（2026-11-12）

- 现象：Redis 被清空、回滚被重复执行、消费者丢批之后，配额 / Redis / 仓库三者对不上，
  但没有任何地方会主动发现，只能等用户投诉「抢到了没库存」
- 修复：
  - `seckill_event.warehouse_baseline` 在预热成功时写入（D0），
    没有基线就查不出「仓库少了货却没有订单」
  - `StockReconcileService` 双侧判定：配额侧 `Q - R = O - X + 在途`，
    仓库侧 `D0 - D = O - X + 在途`；`在途` 取主队列 + 回滚队列的 ready 消息数
  - 同商品已有更新场次时判 `STALE`（Redis 键与 `product_stock` 都按商品维护，旧场次读数已被覆盖）
  - 只读：不提供一键修复，避免把「订单还没落库」误判成「库存多扣」而自动补库存
  - 服务内每 60 秒扫描，连续两次 DRIFT 才升级为 ERROR；告警是单行 JSON
    `reconcile.alert {...}`（2026-11-12 改，原为 `reconcile.suspect` / `reconcile.mismatch`）
  - `fingerprint` 把 DRIFT 归到可能成因（`DUPLICATE_ROLLBACK` / `*_OVER_ROLLBACK` /
    `ROLLBACK_MISSED` / `MIXED_SIGN`），同一实现同时供告警日志与 Agent 诊断使用
- 验收证据：`acceptance_reconcile.py` **PASS 18 / FAIL 0**（正常 OK、Redis 手改 2 件 → 配额侧 DRIFT、
  仓库手改 2 件 → 仓库侧 DRIFT、恢复后重新 OK）
- 已知局限：`在途` 只统计 ready 消息，不含已投递未 ack 的那一批，
  所以活动高峰可能瞬时出现假 DRIFT；靠「连续两次才告警」压掉
- 已知局限二：`STALE` 只判「同商品有更新的场次」。若该活动是最新场次，
  但后续有别的脚本/手工操作改过同一商品的 Redis 或 `product_stock`
  （例如跑完 `acceptance_m2.py` 再跑 `acceptance_dlq.py`，两者都用商品 2），
  它会持续报 `DRIFT`。判断是否代码回归前先看该商品最近有没有被别的脚本动过

---

## 4. P2 级（记录，暂不处理）

| # | 问题 | 位置 | 后果 |
|---|---|---|---|
| P2-1 | 用户标记 `SETEX` 写死 1 小时 | `seckill.lua:20` | 活动跨 1 小时，同一用户能抢两次 |
| P2-2 | `rollbackStock` 的 Redis 侧仍不幂等、`INCR` 可创建脏 key | `SeckillRedisScript.java:50-58` | DB 侧已由台账挡住（P1-7），Redis 侧靠「台账判重后绝不重试」兜底；Redis 调用本身失败时只能少卖等对账 |
| P2-7 | 回滚队列没有 DLX | `RabbitMQConfig.stockRollbackQueue` | 消费者只在 DB 故障时 `requeue=true`，没有死信兜底；补 DLX 必须先删队列（参数不可变更） |
| P2-8 | 普通订单 `createOrder` 是死代码，且会给 `product_stock` 加库存 | `OrderServiceImpl.createOrder` | 目前没有 Controller 调它；一旦接上「下单 → 取消」，普通商品取消会错误地把秒杀仓库 +1 |
| P2-9 | 回滚消息的 `userId` 缺失时整条丢弃 | `StockRollbackConsumer.parse` | 无法删 Redis 用户标记 → 该用户本场活动不能再抢；重新预热（reset）可恢复 |
| P2-3 | Redisson 租约 30 秒，不自动续期 | `SeckillConsumer.java:68` | 批处理超 30 秒，锁提前释放，靠乐观锁兜底 |
| P2-4 | Semaphore 单机限流 | `SeckillController.java:25` | 多副本时实际阈值 = 200 × 副本数 |
| P2-5 | `seckill()` 上的 `@Transactional` 无意义 | `SeckillServiceImpl.java:45` | 方法内无 DB 写操作，注解误导读者 |
| P2-6 | 订单号可能撞号 | `SeckillServiceImpl.java:98-101` | 靠 `uk_order_no` 兜底，撞了就整批失败 |

---

## 5. 修复顺序建议

| 顺序 | 内容 | 依赖 | 状态 |
|---|---|---|---|
| 1 | P0-1 步骤 1（死信队列） | 无 | ✅ 2026-11-12 |
| 2 | P0-1 步骤 2 + 3（只回补失败组 + 事务生效） | 步骤 1 | ✅ 2026-11-12 |
| 3 | P0-2（支付语义 + 前端轮询） | 无 | 🟡 后端已完成 2026-11-12（前端轮询未做） |
| 4 | P1-3（消费幂等） | 步骤 1 | ✅ 2026-11-12 |
| 5 | P1-4（状态 CAS） | 无 | ✅ 2026-11-12 |
| 6 | P0-1 步骤 4 + 5（DLQ 重放脚本） | 步骤 1 | ✅ 2026-11-12 |
| 7 | P1-6 双向自动对账 + 仓库基线 | 无 | ✅ 2026-11-12 |
| 8 | P1-7 库存回滚幂等（台账 + DB 先 Redis 后） | 无 | ✅ 2026-11-12 |
| 9 | 阶段 4：统计补 `cancelledCount`、对账补 `fingerprint`、告警改单行 JSON、Agent 诊断改走 `/reconcile` | 步骤 7 | ✅ 2026-11-12 |

**建议一个独立分支 `fix/seckill-reliability`，按顺序一次一项，每项改完跑回归。**

---

## 6. 回归清单（每次改动后必跑）

```powershell
python scripts/acceptance_m2.py          # 要求 34/34
python scripts/acceptance_m3.py          # 要求 21/21
python scripts/acceptance_dlq.py         # 要求 10/10
python scripts/acceptance_reconcile.py   # 要求 18/18
python scripts/acceptance_stage3.py      # 要求 28/28
mvn -q -pl seckill-service,order-service -am test   # 单测全绿
cd agent-service && .venv/Scripts/python -m pytest -q          # 要求 47 passed
cd agent-service && .venv/Scripts/python ../scripts/acceptance_m4.py  # 要求 26/26
```

注意：`acceptance_m2.py` / `acceptance_m3.py` 会留下 15 分钟后的延迟取消消息，
两次运行间隔太近时会互相影响库存读数（`acceptance_dlq.py` 已有同样现象）。
出现异常先隔 15 分钟重跑一次再判断是不是回归。

另外手动确认三件事：

1. `docker exec flashmall-redis redis-cli GET seckill:stock:7` 与 `product_stock.stock` 的差值，等于未支付订单数
2. RabbitMQ 管理台里 `seckill.order.queue` 的 ready 消息数在活动结束后归零
3. 下一节列出的人工场景逐条过一遍

---

## 7. 明确不修（留理由，避免反复讨论）

| 项 | 理由 |
|---|---|
| 运营把活动库存填 0 | 属于操作失误，代码防不住；应由后台表单校验 + 上线检查清单解决 |
| 活动绑定的 productId 配错 | 同上，属于配置问题 |
| 服务器时区被改回 UTC | `docker-compose.yml` 已给所有服务注入 `TZ=Asia/Shanghai`；再改回来属于环境事故 |
| Redis 重启丢最后 1 秒 AOF | 属于运维预案（`appendfsync always` 有性能代价），不为此改业务代码 |
| 同一用户连点两次返回「已抢购过」 | **这不是缺陷**，是 Lua 的正常防护 |

---

## 8. 决策记录

| 日期 | 决定 | 原因 |
|---|---|---|
| 2026-11-10 | P0-1、P0-2 标记为必修，但**暂不动手** | 当前阶段是理解项目 + 补 AB 档；修可靠性要解除受保护文件约束，需独立排期 |
| 2026-11-10 | 其他项先记录，不在面试前修 | 面试讲得清「已知 + 正确做法」比修掉更值钱；系统加固放在拿到面试机会之后 |
| 2026-11-12 | 解除 `SeckillConsumer` 约束，按四阶段补可靠性缺口 | 用户明确「解锁」；`seckill.lua` 与 `decrementStock` 仍未动 |
| 2026-11-12 | 阶段 1 完成：死信 + 幂等 + 分组回补 + 事务生效 + 重放接口 | 端到端 10/10，回归 34/34 与 21/21 |
| 2026-11-12 | 死信只做手动重放，不做自动重放 | 死信多为业务失败，自动重放会把同一个错误放大成死循环 |
| 2026-11-12 | 阶段 2 完成：双向对账 + 仓库基线，只报警不自动修 | 偏差分不清「Redis 多扣」和「订单未落库」，自动修会把延迟变成超卖 |
| 2026-11-12 | 对账基线 D0 存 MySQL 不存 Redis | Redis 重启恰恰是最需要基线的时候 |
| 2026-11-12 | 阶段 3 完成：订单状态 CAS + 库存回滚幂等台账 + P1-1 反向验证 | `acceptance_stage3.py` 28/28，四条回归 34/21/18/10 全绿 |
| 2026-11-12 | 回滚幂等键用 `orderId`，不用 `{productId,userId}` | 回滚会删 Redis 用户标记，同一用户可以再抢再取消，参数完全一样 |
| 2026-11-12 | 库存回滚顺序定为「先 DB 后 Redis」 | 中间崩溃只会造成「少卖」，对账可发现；反过来是「多卖」，不可修 |
| 2026-11-12 | Redis 回滚失败时 ack 而不是重投 | Redis 回滚无幂等保护，重投可能多 +1；DB 已退、Redis 未退的差异交给对账 |
| 2026-11-12 | 回滚台账用独立表 `stock_rollback_log`，不挂在订单状态上 | 回滚有两个来源（CANCEL / TIMEOUT），订单状态只能表达最终态，看不出谁在什么时候退的 |
| 2026-11-12 | 老格式回滚消息（缺 orderId）打 ERROR 后丢弃 | 无法去重；多退（超卖）不可修，少退能被对账发现并人工补 |
| 2026-11-12 | 阶段 4 完成：`cancelledCount` + `fingerprint` + 结构化告警 + Agent 双向诊断 | 单测 134 绿；回归 21/21、18/18、28/28、Agent e2e 26/26 |
| 2026-11-12 | 对账成因用 `fingerprint` 表达，Agent 只翻译不重算 | 两端各算一遍口径迟早不一致；判定留在 Java（业务真相源） |
| 2026-11-12 | 告警日志改单行 JSON `reconcile.alert`，字段与接口同名 | 原来的 `key=value` 只能肉眼看，无法按 `fingerprint`/`eventId` 聚合 |
| 2026-11-12 | 诊断工具改走 `GET /reconcile`，不再逐个拉 `stats` 自己拼 | 旧口径漏掉仓库侧，且「净消耗 > 订单数」会把 PENDING 误报成漂移 |
