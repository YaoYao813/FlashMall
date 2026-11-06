"""工具返回裁剪：控制上下文长度，避免把整页数据塞给模型。

契约 §3：列表最多保留 10 条、长文本截断到 500 字。
"""

MAX_ITEMS = 10
MAX_TEXT = 500

EVENT_FIELDS = (
    "id",
    "productId",
    "productName",
    "seckillPrice",
    "stock",
    "startTime",
    "endTime",
    "status",
)


def clip_text(value: str | None, limit: int = MAX_TEXT) -> str | None:
    if value is None:
        return None
    text = str(value)
    return text if len(text) <= limit else text[:limit] + "…"


def trim_events(records: list[dict], limit: int = MAX_ITEMS) -> list[dict]:
    trimmed = []
    for record in records[:limit]:
        item = {key: record.get(key) for key in EVENT_FIELDS if key in record}
        if "productName" in item:
            item["productName"] = clip_text(item["productName"])
        trimmed.append(item)
    return trimmed
