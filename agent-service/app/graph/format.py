"""把工具返回转成中文回复。

刻意不让 LLM 生成最终结论：数字必须可复现，措辞固定也方便评测。
"""

from typing import Any


def _time(value: Any) -> str:
    text = str(value or "")
    return text.replace("T", " ")[:16]


def _money(value: Any) -> str:
    return f"¥{float(value):.2f}" if value is not None else "未知"


def render_events(data: dict) -> str:
    items = data.get("items") or []
    if not items:
        return data.get("message") or "没有符合条件的活动。"
    total = data.get("total", len(items))
    pages = data.get("pages", 1)
    lines = [f"共 {total} 个活动（第 1/{pages} 页，最多展示 10 条）："]
    for index, item in enumerate(items, start=1):
        lines.append(
            f"{index}. 活动 {item.get('id')} · 商品 {item.get('productId')} "
            f"{item.get('productName') or ''} · {_money(item.get('seckillPrice'))} · "
            f"库存 {item.get('stock')} · {_time(item.get('startTime'))} ~ "
            f"{_time(item.get('endTime'))} · {item.get('status')}"
        )
    return "\n".join(lines)


def render_stats(data: dict) -> str:
    return data.get("conclusion") or "没有拿到统计数据。"


def render_diagnose(data: dict) -> str:
    lines = [data.get("message") or "检查完成。"]
    for item in data.get("suspicious") or []:
        lines.append(
            f"· 活动 {item.get('eventId')}（商品 {item.get('productId')}）："
            f"活动库存 {item.get('stockTotal')}，Redis 剩余 {item.get('redisStock')}，"
            f"净消耗 {item.get('stockConsumed')}，订单数 {item.get('orderCount')}，"
            f"仓库剩余 {item.get('dbStock')}。"
            + "；".join(item.get("reasons") or [])
        )
    return "\n".join(lines)


def render_write(payload: dict) -> str:
    data = payload.get("data") or {}
    if payload.get("ok"):
        return data.get("conclusion") or "操作已完成。"
    error = payload.get("error") or {}
    message = error.get("message") or "操作失败。"
    if data.get("event"):
        event = data["event"]
        message += f"（活动 id={event.get('id')}，商品 {event.get('productId')}）"
    return message


def render_result(intent: str, payload: dict | None) -> str:
    if payload is None:
        return "没有拿到结果，请稍后重试。"
    if not payload.get("ok", False):
        error = payload.get("error") or {}
        return error.get("message") or "操作失败，请稍后重试。"

    data = payload.get("data") or {}
    if intent == "query_events":
        return render_events(data)
    if intent == "query_stats":
        return render_stats(data)
    if intent == "diagnose":
        return render_diagnose(data)
    if intent in ("create_event", "adjust_stock"):
        return render_write(payload)
    return str(data)


UNKNOWN_REPLY = (
    "我只能处理运营侧的秒杀问题：查活动列表、看活动经营数据、诊断库存漂移、"
    "创建活动、调整进行中活动的库存。你想做哪一件？"
)
