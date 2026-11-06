"""FastAPI 入口：SSE 对话 + 确认恢复 + 页面。

资源生命周期（必须全局一份）：
- MCP stdio 会话：常驻子进程，所有请求共用
- AsyncRedisSaver：checkpoint 存在 Redis Stack 6381，每请求新建会丢挂起状态

事件流：
meta{thread_id} → trace{tool,...}* → (interrupt{card} | message{text} → done)
出错统一 error{code,message}，不把堆栈给前端。
"""

import json
import logging
from contextlib import AsyncExitStack, asynccontextmanager
from pathlib import Path
from uuid import uuid4

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, StreamingResponse
from langchain_core.messages import AIMessage, HumanMessage
from langgraph.checkpoint.redis.aio import AsyncRedisSaver
from langgraph.types import Command
from pydantic import BaseModel, Field

from app.config import settings
from app.graph.builder import build_graph
from app.mcp_client import get_toolbox

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
logger = logging.getLogger(__name__)

WEB_DIR = Path(__file__).resolve().parent.parent / "web"

SSE_HEADERS = {
    "Cache-Control": "no-cache",
    "X-Accel-Buffering": "no",
    "Connection": "keep-alive",
}


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings.require_llm_api_key()  # 缺 key 直接启动失败，别等到第一次对话
    async with AsyncExitStack() as stack:
        saver = await stack.enter_async_context(
            AsyncRedisSaver(redis_url=settings.redis_url)
        )
        toolbox = get_toolbox()
        await toolbox.start()
        stack.push_async_callback(toolbox.stop)
        app.state.graph = build_graph(saver)
        logger.info("Agent 就绪：thread checkpoint=%s，MCP 已连接", settings.redis_url)
        yield


app = FastAPI(title="FlashMall Seckill Copilot", lifespan=lifespan)


class ChatRequest(BaseModel):
    thread_id: str | None = None
    message: str = Field(min_length=1)


class ResumeRequest(BaseModel):
    approved: bool
    feedback: str = ""


def _sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


def _config(thread_id: str) -> dict:
    return {"configurable": {"thread_id": thread_id}}


def _trace(node: str, tool: str, result: dict) -> dict:
    data = result.get("data") or {}
    error = result.get("error") or {}
    return {
        "tool": tool,
        "phase": "done" if result.get("ok") else "failed",
        "ok": bool(result.get("ok")),
        "replayed": data.get("replayed"),
        "message": error.get("message"),
        # 活动列表额外带结构化条目，前端渲染成表格；不解析渲染好的中文（改一个字就静默失效）
        "items": data.get("items") if isinstance(data.get("items"), list) else None,
        "total": data.get("total"),
    }


async def _event_stream(graph, thread_id: str, payload, is_resume: bool):
    yield _sse("meta", {"thread_id": thread_id})
    config = _config(thread_id)
    tool = ""
    try:
        async for chunk in graph.astream(payload, config, stream_mode="updates"):
            for node, update in chunk.items():
                if node == "__interrupt__":
                    interrupts = update if isinstance(update, tuple) else (update,)
                    yield _sse("interrupt", interrupts[0].value)
                    return
                if not isinstance(update, dict):
                    continue
                if node == "router":
                    tool = update.get("tool_name") or ""
                elif node == "read":
                    yield _sse("trace", _trace(node, tool, update.get("tool_result") or {}))
                elif node == "execute":
                    yield _sse("trace", _trace(node, tool, update.get("result") or {}))
                elif node in ("respond", "clarify") and update.get("reply"):
                    yield _sse("message", {"text": update["reply"]})
    except Exception:  # 流已开始，只能写 error 事件
        logger.exception("SSE 会话 %s 失败（resume=%s）", thread_id, is_resume)
        yield _sse("error", {"code": "INTERNAL", "message": "处理失败，请稍后重试"})
        return
    yield _sse("done", {})


@app.post("/api/chat")
async def chat(body: ChatRequest, request: Request):
    graph = request.app.state.graph
    thread_id = body.thread_id or uuid4().hex
    if body.thread_id:
        snapshot = await graph.aget_state(_config(thread_id))
        if snapshot.interrupts:
            # 挂起时插新消息会把确认流程顶掉，先让用户处理卡片
            raise HTTPException(status_code=409, detail="请先批准或拒绝待确认的操作")
    payload = {"messages": [HumanMessage(content=body.message)]}
    return StreamingResponse(
        _event_stream(graph, thread_id, payload, is_resume=False),
        media_type="text/event-stream",
        headers=SSE_HEADERS,
    )


@app.post("/api/chat/{thread_id}/resume")
async def resume(thread_id: str, body: ResumeRequest, request: Request):
    graph = request.app.state.graph
    snapshot = await graph.aget_state(_config(thread_id))
    if not snapshot.interrupts:
        raise HTTPException(status_code=409, detail="该会话没有待确认的操作")
    payload = Command(
        resume={"approved": body.approved, "feedback": body.feedback}
    )
    return StreamingResponse(
        _event_stream(graph, thread_id, payload, is_resume=True),
        media_type="text/event-stream",
        headers=SSE_HEADERS,
    )


@app.get("/api/chat/{thread_id}/state")
async def state(thread_id: str, request: Request):
    graph = request.app.state.graph
    snapshot = await graph.aget_state(_config(thread_id))
    messages = []
    for message in snapshot.values.get("messages", []):
        if isinstance(message, HumanMessage):
            messages.append({"role": "user", "text": message.content})
        elif isinstance(message, AIMessage):
            messages.append({"role": "assistant", "text": message.content})
    pending = snapshot.interrupts[0].value if snapshot.interrupts else None
    return {"thread_id": thread_id, "pending": pending, "messages": messages}


@app.get("/")
async def index():
    file = WEB_DIR / "index.html"
    if not file.exists():
        raise HTTPException(status_code=404, detail="页面还没生成")
    return FileResponse(file)


@app.get("/health")
async def health():
    return {"ok": True, "mcp": get_toolbox().started}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("app.main:app", host="127.0.0.1", port=8000, reload=False)
