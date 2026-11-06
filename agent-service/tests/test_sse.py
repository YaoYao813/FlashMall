"""SSE 事件构造：前端卡片直接吃 trace 里的结构化条目，不解析渲染好的中文。

这条契约要是断了，页面会静默退回纯文本（看起来「没坏」，只是卡片没了）。
"""

from app.main import _trace


def test_trace_carries_items_for_table_render():
    items = [
        {
            "id": 32,
            "productId": 2,
            "productName": "SpringBoot实战指南",
            "seckillPrice": 39.9,
            "stock": 50,
            "startTime": "2026-11-05T21:02:20",
            "endTime": "2026-11-05T21:07:20",
            "status": "running",
        }
    ]
    trace = _trace("read", "query_seckill_events", {"ok": True, "data": {"items": items, "total": 1}})

    assert trace["tool"] == "query_seckill_events"
    assert trace["ok"] is True
    assert trace["items"] == items
    assert trace["total"] == 1


def test_trace_has_no_items_for_non_list_tools():
    trace = _trace("read", "diagnose_stock", {"ok": True, "data": {"message": "库存一致。"}})

    assert trace["items"] is None
    assert trace["total"] is None


def test_trace_keeps_error_message_on_failure():
    trace = _trace(
        "execute",
        "create_seckill_event",
        {"ok": False, "error": {"code": "BIZ", "message": "商品已被占用"}},
    )

    assert trace["ok"] is False
    assert trace["phase"] == "failed"
    assert trace["items"] is None
    assert trace["message"] == "商品已被占用"
