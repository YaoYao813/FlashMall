"""读分支：一个节点跑三种读工具，工具名与参数由 router 决定。"""

import logging
import time

from langchain_core.messages import AIMessage

from app.graph.format import UNKNOWN_REPLY, render_result
from app.mcp_client import get_toolbox

logger = logging.getLogger(__name__)


async def read_node(state: dict) -> dict:
    tool_name = state.get("tool_name")
    args = state.get("tool_args") or {}
    started = time.perf_counter()
    try:
        result = await get_toolbox().call_tool(tool_name, args)
    except Exception:  # 会话异常也要落到人话
        logger.exception("读工具 %s 调用失败", tool_name)
        result = {
            "ok": False,
            "error": {"code": "INTERNAL", "message": "查询失败，请稍后重试"},
        }
    duration = int((time.perf_counter() - started) * 1000)
    logger.info("tool=%s args=%s ok=%s %sms", tool_name, args, result.get("ok"), duration)
    return {"tool_result": result}


async def respond_node(state: dict) -> dict:
    if state.get("intent") == "unknown":
        text = UNKNOWN_REPLY
    else:
        # 按意图取对应结果，避免上一轮遗留字段串台
        intent = state.get("intent", "")
        payload = (
            state.get("result")
            if intent in ("create_event", "adjust_stock")
            else state.get("tool_result")
        )
        text = render_result(intent, payload)
    # 助手回复也进历史，下一轮 router 才能理解「那它呢」这类追问
    return {"reply": text, "messages": [AIMessage(content=text)]}
