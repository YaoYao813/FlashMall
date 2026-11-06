"""router 的结构化输出与槽位规则。

router 只做分类和抽槽，不调用工具；intent 与 slots 由一次 LLM 调用同时产出。
"""

from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, Field

Intent = Literal[
    "query_events",
    "query_stats",
    "diagnose",
    "create_event",
    "adjust_stock",
    "unknown",
]

READ_INTENTS = {"query_events", "query_stats", "diagnose"}
WRITE_INTENTS = {"create_event", "adjust_stock"}

REQUIRED_SLOTS = {
    "query_stats": ["eventId"],
    "create_event": ["productId", "seckillPrice", "stock", "startTime", "endTime"],
    "adjust_stock": ["eventId", "delta", "reason"],
}

SLOT_LABELS = {
    "productId": "商品 id",
    "seckillPrice": "秒杀价",
    "stock": "活动库存数量",
    "startTime": "开始时间",
    "endTime": "结束时间",
    "eventId": "活动 id",
    "delta": "调整数量（正数增加、负数减少）",
    "reason": "调整原因",
}


class RouterDecision(BaseModel):
    intent: Intent = Field(description="用户意图")
    slots: dict[str, Any] = Field(
        default_factory=dict,
        description="已从用户话里明确得到的参数，不要猜；没说的不要填",
    )


def missing_slots(intent: str, slots: dict[str, Any]) -> list[str]:
    return [
        name
        for name in REQUIRED_SLOTS.get(intent, [])
        if slots.get(name) in (None, "")
    ]


def _to_datetime(value: Any) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        return datetime.fromisoformat(value.strip().replace(" ", "T"))
    except ValueError:
        return None


def _to_float(value: Any) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def validate_slots(intent: str, slots: dict[str, Any]) -> list[str]:
    """跨字段校验：槽位齐全不代表取值合法。

    Java 侧也会拒绝这些请求，但拒绝发生在用户「批准」之后——
    确认卡片是用户唯一的决策依据，不能在上面摆一个注定失败的参数组合。
    规则与 Java 侧保持一致，不新造口径。
    解析不了的值不在这里判（交给 Java），只判能确定错的。
    """
    problems: list[str] = []
    if intent == "create_event":
        price = _to_float(slots.get("seckillPrice"))
        if price is not None and price <= 0:
            problems.append("秒杀价必须大于 0")
        stock = _to_float(slots.get("stock"))
        if stock is not None and stock <= 0:
            problems.append("活动库存数量必须大于 0")
        start = _to_datetime(slots.get("startTime"))
        end = _to_datetime(slots.get("endTime"))
        if start is not None and end is not None and end <= start:
            problems.append("结束时间必须晚于开始时间")
    elif intent == "adjust_stock":
        delta = _to_float(slots.get("delta"))
        if delta is not None and delta == 0:
            problems.append("调整数量不能为 0")
    return problems


def normalize_slots(intent: str, raw: dict[str, Any]) -> dict[str, Any]:
    """把模型给的字面量转成工具需要的类型；转不了的保留原值让后续追问。"""
    slots = {k: v for k, v in (raw or {}).items() if v not in (None, "")}
    int_fields = {
        "query_events": ("productId", "page"),
        "query_stats": ("eventId",),
        "diagnose": ("eventId",),
        "create_event": ("productId", "stock"),
        "adjust_stock": ("eventId", "delta"),
    }.get(intent, ())
    for name in int_fields:
        if name not in slots:
            continue
        try:
            slots[name] = int(float(slots[name]))
        except (TypeError, ValueError):
            slots.pop(name, None)
    if intent == "create_event" and "seckillPrice" in slots:
        try:
            slots["seckillPrice"] = float(slots["seckillPrice"])
        except (TypeError, ValueError):
            slots.pop("seckillPrice", None)
    if intent == "adjust_stock" and "reason" in slots:
        slots["reason"] = str(slots["reason"]).strip()
    return slots
