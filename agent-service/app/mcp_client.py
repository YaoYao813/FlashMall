"""常驻 MCP stdio 会话。

为什么不是每个工具调用起一个子进程：冷启动要重新 import、重新登录，
一次对话里 3–5 次调用会明显变慢，而且日志零散。

会话由 FastAPI lifespan / CLI 启动，图节点通过 `call_tool()` 调用。
"""

import json
import logging
import os
import sys
from contextlib import AsyncExitStack

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

logger = logging.getLogger(__name__)


class MCPToolbox:
    def __init__(self) -> None:
        self._stack: AsyncExitStack | None = None
        self._session: ClientSession | None = None

    @property
    def started(self) -> bool:
        return self._session is not None

    async def start(self) -> None:
        if self._session is not None:
            return
        stack = AsyncExitStack()
        params = StdioServerParameters(
            command=sys.executable,
            args=["-m", "mcp_servers.commerce_server"],
            env={**os.environ, "PYTHONIOENCODING": "utf-8"},
        )
        try:
            read, write = await stack.enter_async_context(stdio_client(params))
            session = await stack.enter_async_context(ClientSession(read, write))
            await session.initialize()
        except Exception:
            await stack.aclose()
            raise
        self._stack = stack
        self._session = session
        logger.info("MCP 会话已启动")

    async def stop(self) -> None:
        if self._stack is not None:
            await self._stack.aclose()
        self._stack = None
        self._session = None

    async def call_tool(self, name: str, arguments: dict) -> dict:
        """调用工具并把 JSON 文本解析成 dict；协议层错误转成统一失败封套。"""
        if self._session is None:
            raise RuntimeError("MCP 会话未启动")
        result = await self._session.call_tool(name, arguments)
        # mcp 2.x 字段名从 isError 改成了 is_error；兼容两者，避免 SDK 升级再踩
        if getattr(result, "is_error", getattr(result, "isError", False)):
            logger.error("MCP 工具 %s 协议错误：%s", name, result.content)
            return {
                "ok": False,
                "error": {"code": "MCP_ERROR", "message": "工具调用失败，请稍后重试"},
            }
        for block in result.content:
            text = getattr(block, "text", None)
            if not text:
                continue
            try:
                payload = json.loads(text)
            except ValueError:
                continue
            if isinstance(payload, dict):
                return payload
        logger.error("MCP 工具 %s 返回了无法解析的内容", name)
        return {
            "ok": False,
            "error": {"code": "MCP_ERROR", "message": "工具返回格式异常，请稍后重试"},
        }


_toolbox: MCPToolbox | None = None


def get_toolbox() -> MCPToolbox:
    global _toolbox
    if _toolbox is None:
        _toolbox = MCPToolbox()
    return _toolbox
