"""命令行调试入口：先验证图，再接 FastAPI。

用法（在 agent-service 目录）：
    .venv/Scripts/python -m app.cli

命令：`/exit` 退出，`/new` 新会话（新的 thread_id）。
写操作会打印确认卡片，输入 y 批准、其他键拒绝。
"""

import asyncio
import logging
from uuid import uuid4

from langchain_core.messages import HumanMessage
from langgraph.checkpoint.redis.aio import AsyncRedisSaver
from langgraph.types import Command

from app.config import settings
from app.graph.builder import build_graph
from app.mcp_client import get_toolbox

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
logging.getLogger("httpx").setLevel(logging.WARNING)


def _print_card(card: dict) -> None:
    print("\n=== 确认卡片 ===")
    print(card.get("title", "写操作"))
    for field in card.get("fields", []):
        print(f"  {field.get('label')}: {field.get('value')}")
    print(f"  影响：{card.get('impact')}")
    print("================")


async def _handle_interrupt(graph, result: dict, config: dict) -> dict:
    interrupts = result.get("__interrupt__") or ()
    if not interrupts:
        return result
    card = interrupts[0].value
    _print_card(card)
    answer = input("批准执行？(y/N) ").strip().lower()
    return await graph.ainvoke(
        Command(resume={"approved": answer == "y", "feedback": ""}), config
    )


async def main() -> None:
    async with AsyncRedisSaver(redis_url=settings.redis_url) as saver:
        toolbox = get_toolbox()
        await toolbox.start()
        graph = build_graph(saver)
        thread_id = f"cli-{uuid4().hex[:8]}"
        config = {"configurable": {"thread_id": thread_id}}
        print(f"会话 {thread_id}，输入 /exit 退出，/new 新建会话")

        try:
            while True:
                text = (await asyncio.to_thread(input, "你> ")).strip()
                if not text:
                    continue
                if text in ("/exit", "exit", "quit"):
                    break
                if text == "/new":
                    thread_id = f"cli-{uuid4().hex[:8]}"
                    config = {"configurable": {"thread_id": thread_id}}
                    print(f"新会话 {thread_id}")
                    continue

                result = await graph.ainvoke(
                    {"messages": [HumanMessage(content=text)]}, config
                )
                result = await _handle_interrupt(graph, result, config)
                print(f"助手> {result.get('reply', '（无回复）')}\n")
        finally:
            await toolbox.stop()


if __name__ == "__main__":
    asyncio.run(main())
