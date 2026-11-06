"""FlashMall 运营 MCP Server：5 个工具（3 读 2 写）。

传输方式为 stdio：`python -m mcp_servers.commerce_server`。
stdout 是协议通道，任何日志都必须走 stderr（logging 默认如此），不要 print。

工具只做三件事：参数校验 → 调 `FlashMallClient` → 统一封套。
业务判断（缺参数追问、确认卡片、幂等键生成）在 LangGraph 里，不在 MCP 层。

写工具的 `idempotencyKey` 由图的 `plan_write` 节点注入（`{thread_id}:{tool_call_id}`），
模型不直接调用工具 schema，所以这里不会由模型填写。
"""

import logging
from typing import Annotated, Any, Literal

from mcp.server.mcpserver import MCPServer
from pydantic import Field

from app.clients.errors import FlashMallError
from app.clients.flashmall import get_client
from app.guardrails.trim import clip_text, trim_events

logger = logging.getLogger(__name__)

mcp = MCPServer("flashmall-commerce")

QUERY_EVENTS_DESC = (
    "查询秒杀活动列表。用户问「有哪些活动 / 某个商品的活动状态 / 活动什么时候开始」时使用。"
    "不要用它做销量或金额统计（那是 get_seckill_stats），"
    "不要用它判断库存是否一致（那是 diagnose_stock）。"
)


def _ok(data: Any) -> dict:
    return {"ok": True, "data": data}


def _fail(code: str, message: str, data: Any = None) -> dict:
    result = {"ok": False, "error": {"code": code, "message": message}}
    if data is not None:
        result["data"] = data
    return result


def _fail_from(exc: Exception) -> dict:
    if isinstance(exc, FlashMallError):
        return _fail(exc.code, exc.message)
    logger.exception("MCP 工具执行失败")
    return _fail("INTERNAL", "工具执行失败，请稍后重试")


# ==================== 读工具 ====================

@mcp.tool(name="query_seckill_events", description=QUERY_EVENTS_DESC)
async def query_seckill_events(
    status: Annotated[
        Literal["all", "pending", "running", "ended"],
        Field(description="活动状态过滤；不知道就传 all"),
    ] = "all",
    productId: Annotated[int | None, Field(ge=1, description="按商品 id 过滤")] = None,
    page: Annotated[int, Field(ge=1, description="页码，从 1 开始")] = 1,
) -> dict:
    try:
        result = await get_client().list_events(status, productId, page, size=10)
    except Exception as exc:  # noqa: BLE001 - 统一转封套，堆栈只进日志
        return _fail_from(exc)

    records = result.get("records") or []
    items = trim_events(records)
    if not items:
        return _ok({"items": [], "total": 0, "message": "没有符合条件的活动"})
    return _ok(
        {
            "items": items,
            "total": result.get("total", len(items)),
            "pages": result.get("pages", 1),
        }
    )


def _percent(value: Any) -> str:
    if value is None:
        return "未知"
    return f"{float(value) * 100:.0f}%"


def _money(value: Any) -> str:
    if value is None:
        return "未知"
    return f"¥{float(value):.2f}"


def _stats_conclusion(event: dict, stats: dict, stock: dict) -> str:
    total = stats.get("stockTotal")
    consumed = stats.get("stockConsumed")
    redis = stock.get("redisStock")
    parts = []
    if consumed is not None and total is not None:
        parts.append(f"已售 {consumed}/{total}")
    if redis is not None:
        parts.append(f"当前剩余 {redis} 件")
    parts.append(f"支付率 {_percent(stats.get('payRate'))}")
    parts.append(f"GMV {_money(stats.get('gmv'))}")
    event_id = event.get("id")
    prefix = f"活动 {event_id}" if event_id is not None else "该活动"
    return f"{prefix}：" + "，".join(parts) + "。"


@mcp.tool(
    name="get_seckill_stats",
    description=(
        "查询某个活动的经营数据（订单数、支付率、GMV、库存消耗率）与当前库存快照。"
        "用户问「卖得怎么样 / 成交多少 / 还剩多少」时使用。"
        "必须有明确的活动 id；用户没说要查哪个活动时先追问。"
    ),
)
async def get_seckill_stats(
    eventId: Annotated[int, Field(ge=1, description="活动 id")],
) -> dict:
    try:
        result = await get_client().get_stats(eventId)
    except Exception as exc:  # noqa: BLE001
        return _fail_from(exc)

    event = result.get("event") or {}
    stats = result.get("stats") or {}
    stock = result.get("stock") or {}
    return _ok(
        {
            "event": event,
            "stats": stats,
            "stock": stock,
            "conclusion": _stats_conclusion(event, stats, stock),
        }
    )


def _diagnose_one(event: dict, stats: dict, stock: dict) -> dict | None:
    """返回可疑记录；库存正常返回 None。

    判定口径（契约 §2.3）：
    - Redis 不可读（redisStock=null）→ 无法确认，直接可疑
    - 净消耗 stockTotal - redisStock 大于本场订单数 → Redis 已经扣了但订单没落库，
      可能是消费者丢消息（nack requeue=false 且无死信，见 CLAUDE.md）
    - redisStock 超出 [0, stockTotal] → 预热/回滚把数量加坏了
    dbStock(product_stock) 与 redisStock 的差值只作为参考信息展示：
    活动配额和仓库余量本来就可能不相等（商品 7 是 30/50），不能拿来判定。
    """
    total = stats.get("stockTotal")
    consumed = stats.get("stockConsumed")
    orders = stats.get("orderCount")
    redis = stock.get("redisStock")

    reasons = []
    if redis is None:
        reasons.append("Redis 库存不可读，无法判断是否漂移")
    else:
        if total is not None and not (0 <= redis <= total):
            reasons.append(f"Redis 剩余 {redis} 超出活动配额范围 0–{total}")
        if consumed is not None and orders is not None and consumed > orders:
            reasons.append(
                f"净消耗 {consumed} 大于订单数 {orders}，可能存在消息未落库"
            )
    if not reasons:
        return None

    return {
        "eventId": event.get("id"),
        "productId": event.get("productId"),
        "stockTotal": total,
        "redisStock": redis,
        "dbStock": stock.get("dbStock"),
        "ordersOutstanding": stock.get("ordersOutstanding"),
        "stockConsumed": consumed,
        "orderCount": orders,
        "reasons": reasons,
    }


@mcp.tool(
    name="diagnose_stock",
    description=(
        "检查库存一致性——对比活动净消耗、订单数与 Redis 实际库存，找出可能的漂移。"
        "用户问「库存对不对 / 有没有超卖 / 为什么抢不到」时使用。"
        "传 eventId 只查一场，不传则查全部进行中的活动。只诊断，不修复。"
    ),
)
async def diagnose_stock(
    eventId: Annotated[int | None, Field(ge=1, description="只查指定活动；不传则查全部进行中")] = None,
) -> dict:
    client = get_client()
    try:
        if eventId is not None:
            event_ids = [eventId]
        else:
            listing = await client.list_events("running", None, 1, size=10)
            records = listing.get("records") or []
            event_ids = [r.get("id") for r in records if r.get("id") is not None]
            if not event_ids:
                return _ok(
                    {"checked": 0, "suspicious": [], "message": "当前没有进行中的活动"}
                )

        suspicious = []
        for current_id in event_ids:
            result = await client.get_stats(current_id)
            event = result.get("event") or {}
            stats = result.get("stats") or {}
            stock = result.get("stock") or {}
            finding = _diagnose_one(event, stats, stock)
            if finding is not None:
                suspicious.append(finding)
    except Exception as exc:  # noqa: BLE001
        return _fail_from(exc)

    checked = len(event_ids)
    if suspicious:
        message = f"检查了 {checked} 个活动，发现 {len(suspicious)} 个可能有库存漂移。"
    else:
        message = f"检查了 {checked} 个活动，未发现明显的库存漂移。"
    return _ok({"checked": checked, "suspicious": suspicious, "message": message})


# ==================== 写工具 ====================


@mcp.tool(
    name="create_seckill_event",
    description=(
        "创建秒杀活动并预热库存。用户给出商品、价格、数量、时间区间时使用。"
        "缺任何一个必填参数都必须先追问，不要猜。时间按用户所在时区（UTC+8）理解。"
    ),
)
async def create_seckill_event(
    productId: Annotated[int, Field(ge=1, description="商品 id")],
    seckillPrice: Annotated[float, Field(gt=0, description="秒杀价（元）")],
    stock: Annotated[int, Field(ge=1, description="活动库存数量")],
    startTime: Annotated[str, Field(description="开始时间，ISO8601，如 2026-11-11T10:00:00")],
    endTime: Annotated[str, Field(description="结束时间，ISO8601")],
    idempotencyKey: Annotated[
        str, Field(description="由 Agent 图的 plan_write 节点生成，模型不得填写")
    ] = "",
) -> dict:
    if not idempotencyKey:
        return _fail("BAD_REQUEST", "缺少幂等键，写操作必须由 Agent 发起")

    payload = {
        "productId": productId,
        "seckillPrice": seckillPrice,
        "stock": stock,
        "startTime": startTime,
        "endTime": endTime,
    }
    try:
        created = await get_client().create_event(payload, idempotencyKey)
    except Exception as exc:  # noqa: BLE001
        return _fail_from(exc)

    event_id = created.get("id")
    try:
        sync = await get_client().stock_sync_reset(event_id)
    except FlashMallError as exc:
        # 创建和预热不是原子的：如实报告「建成了但没预热」，不能报整体成功
        return _fail(
            "PREHEAT_FAILED",
            f"活动已创建（id={event_id}），但库存预热失败：{exc.message}。"
            "请先检查仓库库存，再重新预热。",
            data={"event": created},
        )
    except Exception as exc:
        logger.exception("预热活动 %s 失败", event_id)
        return _fail(
            "PREHEAT_FAILED",
            f"活动已创建（id={event_id}），但库存预热失败：{type(exc).__name__}。"
            "请稍后重新预热。",
            data={"event": created},
        )

    return _ok(
        {
            "event": created,
            "preheat": sync,
            "conclusion": (
                f"活动 {event_id} 已创建并完成预热："
                f"活动库存 {sync.get('dbStock')}，Redis 剩余 {sync.get('redisStock')}。"
            ),
        }
    )


@mcp.tool(
    name="adjust_seckill_stock",
    description=(
        "调整进行中活动的库存。用户说「再加 20 件 / 减少 10 件 / 库存改了」时使用。"
        "必须先运行 diagnose_stock 或 get_seckill_stats 确认当前库存，并向用户复述调整前后数字。"
    ),
)
async def adjust_seckill_stock(
    eventId: Annotated[int, Field(ge=1, description="活动 id")],
    delta: Annotated[int, Field(description="正数增加，负数减少，不能为 0")],
    reason: Annotated[str, Field(min_length=1, description="调整原因，写进审计日志")],
    idempotencyKey: Annotated[
        str, Field(description="由 Agent 图的 plan_write 节点生成，模型不得填写")
    ] = "",
) -> dict:
    if delta == 0:
        return _fail("BAD_REQUEST", "库存调整量不能为 0")
    if not idempotencyKey:
        return _fail("BAD_REQUEST", "缺少幂等键，写操作必须由 Agent 发起")
    reason = clip_text(reason) or ""

    try:
        result = await get_client().stock_sync_increment(
            eventId, delta, reason, idempotencyKey
        )
    except Exception as exc:  # noqa: BLE001
        return _fail_from(exc)

    direction = "增加" if delta > 0 else "减少"
    replayed = "（幂等回放，库存未重复调整）" if result.get("replayed") else ""
    return _ok(
        {
            **result,
            "conclusion": (
                f"活动 {eventId} 库存已{direction} {abs(delta)} 件："
                f"活动库存 {result.get('dbStock')}，Redis 剩余 {result.get('redisStock')}{replayed}。"
            ),
        }
    )


if __name__ == "__main__":
    mcp.run(transport="stdio")
