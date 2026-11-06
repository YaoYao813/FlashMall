"""写分支：plan_write → confirm(interrupt) → execute。

幂等键为什么在 plan_write 生成：`interrupt()` 恢复时整个 confirm 节点会从头重跑，
节点里生成的东西不会保留；键必须存在 confirm 之前的 checkpoint 里。
同一条确认恢复多少次，execute 用的都是同一个键，Java 回放不会重复执行。
"""

import logging
from typing import Any
from uuid import uuid4

from langchain_core.runnables import RunnableConfig
from langgraph.types import interrupt

from app.graph.schemas import SLOT_LABELS
from app.mcp_client import get_toolbox

logger = logging.getLogger(__name__)


def _fmt_time(value: Any) -> str:
    return str(value or "").replace("T", " ")[:16]


async def _current_redis_stock(event_id: int) -> int | None:
    """调整库存时读一次当前 Redis 剩余，用于确认卡片的「调整前」。失败不阻断流程。"""
    try:
        payload = await get_toolbox().call_tool("get_seckill_stats", {"eventId": event_id})
    except Exception:
        logger.warning("确认卡片读取活动 %s 当前库存失败", event_id, exc_info=True)
        return None
    if not payload.get("ok"):
        return None
    return ((payload.get("data") or {}).get("stock") or {}).get("redisStock")


async def _build_card(intent: str, args: dict[str, Any]) -> dict:
    if intent == "create_event":
        fields = [
            {"label": SLOT_LABELS["productId"], "value": args.get("productId")},
            {"label": SLOT_LABELS["seckillPrice"], "value": f"¥{args.get('seckillPrice'):.2f}"},
            {"label": SLOT_LABELS["stock"], "value": args.get("stock")},
            {"label": SLOT_LABELS["startTime"], "value": _fmt_time(args.get("startTime"))},
            {"label": SLOT_LABELS["endTime"], "value": _fmt_time(args.get("endTime"))},
        ]
        return {
            "action": intent,
            "title": "创建秒杀活动并预热库存",
            "fields": fields,
            "impact": "会创建活动并写入 Redis 库存窗口，活动开始后可被用户抢购。",
        }

    before = await _current_redis_stock(int(args["eventId"]))
    after = before + int(args["delta"]) if before is not None else None
    fields = [
        {"label": "活动 id", "value": args.get("eventId")},
        {
            "label": "库存变化",
            "value": (
                f"{before} → {after}" if after is not None else f"服务端当前值为准，调整 {args.get('delta'):+d}"
            ),
        },
        {"label": SLOT_LABELS["delta"], "value": f"{int(args.get('delta')):+d}"},
        {"label": SLOT_LABELS["reason"], "value": args.get("reason")},
    ]
    return {
        "action": intent,
        "title": "调整进行中活动的库存",
        "fields": fields,
        "impact": "同步修改 Redis 与数据库库存：增加可能让更多人抢到，减少可能提前售罄。",
    }


async def plan_write_node(state: dict, config: RunnableConfig) -> dict:
    thread_id = str((config.get("configurable") or {}).get("thread_id") or "default")
    intent = state["intent"]
    slots = state.get("slots") or {}

    if intent == "create_event":
        tool_args = {
            "productId": slots["productId"],
            "seckillPrice": slots["seckillPrice"],
            "stock": slots["stock"],
            "startTime": slots["startTime"],
            "endTime": slots["endTime"],
        }
    else:
        tool_args = {
            "eventId": slots["eventId"],
            "delta": slots["delta"],
            "reason": slots["reason"],
        }

    tool_call_id = uuid4().hex
    action = {
        "intent": intent,
        "tool_name": state["tool_name"],
        "tool_args": tool_args,
        "tool_call_id": tool_call_id,
        # 键在这里固定，写进 checkpoint 后才 interrupt
        "idempotency_key": f"{thread_id}:{tool_call_id}",
        "card": await _build_card(intent, tool_args),
    }
    return {"pending_action": action}


def confirm_node(state: dict) -> dict:
    decision = interrupt(state["pending_action"]["card"])
    if isinstance(decision, dict):
        approved = bool(decision.get("approved"))
        feedback = str(decision.get("feedback") or "")
    else:  # 容忍裸 bool，但契约要求传 dict
        approved = bool(decision)
        feedback = ""
    return {"approved": approved, "feedback": feedback}


async def execute_node(state: dict, config: RunnableConfig) -> dict:
    action = state["pending_action"]
    thread_id = str((config.get("configurable") or {}).get("thread_id") or "default")

    if not state.get("approved"):
        # 拒绝：不调工具、不写业务表，Java 侧没有审计行；这里留一条结构化日志
        logger.info(
            "audit.rejected thread_id=%s tool=%s args=%s",
            thread_id,
            action["tool_name"],
            action["tool_args"],
        )
        return {
            "result": {
                "ok": False,
                "error": {"code": "REJECTED", "message": "你已拒绝本次操作，没有做任何修改。"},
            }
        }

    arguments = {**action["tool_args"], "idempotencyKey": action["idempotency_key"]}
    try:
        result = await get_toolbox().call_tool(action["tool_name"], arguments)
    except Exception:
        logger.exception("写工具 %s 调用失败", action["tool_name"])
        result = {
            "ok": False,
            "error": {"code": "INTERNAL", "message": "操作执行失败，请稍后重试"},
        }
    logger.info(
        "audit.executed thread_id=%s tool=%s ok=%s replayed=%s",
        thread_id,
        action["tool_name"],
        result.get("ok"),
        (result.get("data") or {}).get("replayed"),
    )
    return {"result": result}
