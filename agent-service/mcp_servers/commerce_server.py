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
    cancelled = stats.get("cancelledCount")
    if cancelled:
        parts.append(f"取消/超时 {cancelled} 单")
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


# 指纹 → 人话。指纹由 Java 对账服务按两侧 gap 的符号算出来（契约 §1.7），
# Agent 只做翻译，不自己重新判定，避免两端口径漂移。
_FINGERPRINT_HINTS = {
    "DUPLICATE_ROLLBACK": "两侧库存都比应有值多，疑似同一条回滚消息执行了两次（多退，有超卖风险）",
    "REDIS_OVER_ROLLBACK": "Redis 剩余比应有值多，疑似回滚把库存加多了（多退，有超卖风险）",
    "WAREHOUSE_OVER_ROLLBACK": "仓库剩余比应有值多，疑似 product_stock 被多加了库存（多退）",
    "ROLLBACK_MISSED": "库存比应有值少，退单可能没有归还库存，或库存扣了但订单没落库（少退，少卖）",
    "MIXED_SIGN": "两侧偏差方向相反，不能归因为单一原因，需要人工核对",
}


def _side_detail(label: str, side: dict, expected_key: str, actual_key: str) -> str | None:
    gap = side.get("gap")
    if gap is None:
        return None
    expected = side.get(expected_key)
    actual = side.get(actual_key)
    return f"{label}：应为 {expected}，实际 {actual}（gap={gap:+d}）"


def _diagnose_one(reconcile: dict) -> dict | None:
    """把一次对账结果转成可疑记录；OK / PENDING 返回 None。

    判定完全交给 Java 的 `GET /reconcile`（契约 §1.7）：
    OK 与 PENDING（还有消息在队列里）不算问题；
    DRIFT 确认漂移；UNKNOWN（读数缺失）与 STALE（读数已被后来的场次改写）无法下结论，
    但也不能当作「正常」告诉用户。
    """
    verdict = reconcile.get("verdict")
    if verdict in ("OK", "PENDING"):
        return None

    quota = reconcile.get("quota") or {}
    warehouse = reconcile.get("warehouse") or {}
    fingerprint = reconcile.get("fingerprint")
    reasons: list[str] = []

    if verdict == "DRIFT":
        hint = _FINGERPRINT_HINTS.get(fingerprint)
        if hint:
            reasons.append(hint)
        quota_detail = _side_detail("配额侧", quota, "expectedRedis", "redisStock")
        warehouse_detail = _side_detail("仓库侧", warehouse, "expectedDb", "dbStock")
        reasons.extend(detail for detail in (quota_detail, warehouse_detail) if detail)
    elif verdict == "UNKNOWN":
        if quota.get("redisStock") is None:
            reasons.append("Redis 库存不可读，配额侧无法判定")
        if warehouse.get("baseline") is None:
            reasons.append("活动没有预热基线（对账上线前的历史活动），仓库侧无法判定")
        if not reasons:
            reasons.append("缺少读数，无法判定")
    elif verdict == "STALE":
        reasons.append("同一商品已有更新的场次，Redis 与仓库读数已被后来的活动改写，本场无法判定")
    else:
        reasons.append(f"对账返回了未知结论 {verdict}")

    return {
        "eventId": reconcile.get("eventId"),
        "productId": reconcile.get("productId"),
        "status": reconcile.get("status"),
        "verdict": verdict,
        "fingerprint": fingerprint,
        "backlog": reconcile.get("backlog"),
        "stockTotal": quota.get("total"),
        "redisStock": quota.get("redisStock"),
        "orderCount": quota.get("orderCount"),
        "cancelledCount": quota.get("cancelledCount"),
        "dbStock": warehouse.get("dbStock"),
        "quota": quota,
        "warehouse": warehouse,
        "reasons": reasons,
    }


@mcp.tool(
    name="diagnose_stock",
    description=(
        "检查库存一致性——双向对账：配额侧（活动总配额 vs Redis 剩余）与"
        "仓库侧（预热基线 vs product_stock）各自和净订单数比对，找出漂移并给出可能成因。"
        "用户问「库存对不对 / 有没有超卖 / 为什么抢不到」时使用。"
        "传 eventId 只查一场，不传则查全部仍可对账的活动（进行中及刚结束 30 分钟内）。"
        "只诊断，不修复。"
    ),
)
async def diagnose_stock(
    eventId: Annotated[
        int | None, Field(ge=1, description="只查指定活动；不传则查全部仍可对账的活动")
    ] = None,
) -> dict:
    client = get_client()
    try:
        if eventId is not None:
            results = [await client.reconcile(eventId)]
        else:
            results = await client.reconcile_all()
    except Exception as exc:  # noqa: BLE001
        return _fail_from(exc)

    if not results:
        return _ok(
            {"checked": 0, "suspicious": [], "message": "当前没有可对账的活动"}
        )

    suspicious = [finding for finding in map(_diagnose_one, results) if finding is not None]
    checked = len(results)
    if suspicious:
        drift = sum(1 for finding in suspicious if finding["verdict"] == "DRIFT")
        uncertain = len(suspicious) - drift
        message = f"检查了 {checked} 个活动：{drift} 个确认库存漂移"
        if uncertain:
            message += f"，{uncertain} 个缺少读数无法判定"
        message += "。"
    else:
        message = f"检查了 {checked} 个活动，配额侧与仓库侧都能对上。"
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
