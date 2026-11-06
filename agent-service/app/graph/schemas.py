"""router 的结构化输出与槽位规则。

router 只做分类和抽槽，不调用工具；intent 与 slots 由一次 LLM 调用同时产出。
"""

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
