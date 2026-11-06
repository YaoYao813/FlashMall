"""router：一次 LLM 调用同时出 intent 与 slots。

模型只负责「听懂用户」，不负责调用工具；工具选择和参数拼装在代码里，结果可复现。
分类失败（网络、模型不听话）不抛异常，降级成 unknown 让用户重说一遍。
"""

import logging
import re
from datetime import datetime
from typing import Any
from zoneinfo import ZoneInfo

from langchain_core.messages import BaseMessage, HumanMessage, SystemMessage
from langchain_core.runnables import RunnableConfig
from langchain_openai import ChatOpenAI

from app.config import settings
from app.graph.schemas import (
    READ_INTENTS,
    WRITE_INTENTS,
    RouterDecision,
    missing_slots,
    normalize_slots,
)

logger = logging.getLogger(__name__)

TZ = ZoneInfo("Asia/Shanghai")

SYSTEM_PROMPT = """你是 FlashMall 秒杀商城的运营助手，只做意图分类和参数抽取。
当前时间（UTC+8）：{now}

可选 intent：
- query_events：查活动列表、活动状态、活动起止时间
- query_stats：查某个活动的销量、支付率、GMV、剩余库存（必须有 eventId）
- diagnose：查库存是否一致、有没有超卖、为什么抢不到（eventId 可选）
- create_event：创建秒杀活动（需要 productId、seckillPrice、stock、startTime、endTime）
- adjust_stock：调整进行中活动的库存（需要 eventId、delta、reason）
- unknown：与运营无关或无法判断

规则：
1. 只判断「当前用户消息」这一句的意图，**不要回答用户的问题**，不要罗列活动、不要复述历史。
2. 对话历史只用来理解指代（例如「再加 2 件」指上一句提到的活动），不要被上一轮的回答带跑。
3. 只抽用户明确说出的参数，没说的不要填、不要猜。
4. 相对时间换算成 ISO8601（UTC+8），例如「明天下午 3 点」→ 2026-11-06T15:00:00。
5. delta：正数表示增加，负数表示减少。「再加 20 件」→ 20，「减 10 件」→ -10。
6. reason 用用户的原话概括，不要编造。
7. 只输出 JSON，格式：{{"intent": "...", "slots": {{...}}}}，不要输出其他文字。

示例：
用户：有哪些进行中的活动
输出：{{"intent": "query_events", "slots": {{"status": "running"}}}}
用户：商品 7 的活动卖得怎么样
输出：{{"intent": "query_stats", "slots": {{"productId": 7}}}}
用户：看活动经营数据
输出：{{"intent": "query_stats", "slots": {{}}}}
用户：给商品 7 建一个活动，199 块，50 件，明天上午 10 点到 12 点
输出：{{"intent": "create_event", "slots": {{"productId": 7, "seckillPrice": 199, "stock": 50, "startTime": "2026-11-06T10:00:00", "endTime": "2026-11-06T12:00:00"}}}}
"""


def _llm() -> ChatOpenAI:
    return ChatOpenAI(
        model=settings.llm_model,
        api_key=settings.require_llm_api_key(),
        base_url=settings.llm_base_url,
        temperature=0,
        timeout=30,
    )


HISTORY_TURNS = 6
HISTORY_CLIP = 80


def _last_user_text(messages: list[BaseMessage]) -> str:
    for message in reversed(messages):
        if isinstance(message, HumanMessage):
            return str(message.content)
    return ""


def _clip(text: str, limit: int = HISTORY_CLIP) -> str:
    flat = " ".join(str(text).split())
    return flat if len(flat) <= limit else flat[:limit] + "…"


def build_prompt(messages: list[BaseMessage], now: str) -> list[BaseMessage]:
    """历史压成一条 user 消息，而不是按角色原样喂。

    按角色喂时模型会把「上一轮助手的活动列表」当成自己正在写的内容，
    接着往下写而忘记输出 JSON（实测失败回合 4/15，压成一条后 15/15）。
    """
    lines = []
    for message in messages[:-1][-HISTORY_TURNS:]:
        who = "用户" if isinstance(message, HumanMessage) else "助手"
        lines.append(f"{who}：{_clip(str(message.content))}")
    current = _last_user_text(messages)
    body = ""
    if lines:
        body = "对话历史（只用于理解指代，不要照着回答）：\n" + "\n".join(lines) + "\n\n"
    body += f"当前用户消息：{current}"
    return [SystemMessage(content=SYSTEM_PROMPT.format(now=now)), HumanMessage(content=body)]


async def _classify(prompt: list[BaseMessage]) -> RouterDecision | None:
    # DeepSeek 只支持 response_format={"type":"json_object"}，
    # 走默认 json_schema 会直接 400（This response_format type is unavailable now），
    # 所以这里直接用 json_mode，不再先试一次注定失败的请求。
    # include_raw=True：解析失败时能拿到模型原话，否则只有一个 JSONDecodeError。
    structured = _llm().with_structured_output(
        RouterDecision, method="json_mode", include_raw=True
    )
    out = await structured.ainvoke(prompt)
    if out["parsed"] is None:
        logger.warning("router 输出不是合法 JSON：%r", _clip(str(out["raw"].content), 200))
        return None
    return out["parsed"]


# 只在模型判 unknown / 调用失败时兜底：猜不到才是 unknown。
# 写意图即使猜中也要过确认卡片，不会因此误执行。
_KEYWORD_RULES: list[tuple[str, re.Pattern[str]]] = [
    ("create_event", re.compile(r"(建|创建|新建|开|安排|搞)[^，。；]{0,6}(活动|秒杀)")),
    ("adjust_stock", re.compile(r"(加|减|增加|减少|上调|下调|调整|补)[^，。；]{0,6}(件|库存)")),
    ("query_stats", re.compile(r"(卖得|销量|GMV|gmv|支付率|经营数据|成交|卖了)")),
    ("diagnose", re.compile(r"(超卖|抢不到|对不上|漂移|诊断|库存[^，。；]{0,6}(对不对|一致|差))")),
    (
        "query_events",
        re.compile(
            r"(有哪些|有什么|哪几个|列出|列表|进行中|在售)[^，。；]{0,8}(活动|秒杀)"
            r"|(活动|秒杀)[^，。；]{0,6}(有哪些|有什么|列表|列出|进行中|情况)"
        ),
    ),
    ("query_events", re.compile(r"^(查|看|列)?(一下)?(活动|秒杀)(列表|情况)?[？?]?$")),
]


def guess_decision(text: str) -> RouterDecision | None:
    stripped = text.strip()
    for intent, pattern in _KEYWORD_RULES:
        if not pattern.search(stripped):
            continue
        slots: dict[str, Any] = {}
        if intent == "query_events" and re.search(r"进行中|进行的|正在|在售|未结束", stripped):
            slots["status"] = "running"
        return RouterDecision(intent=intent, slots=slots)
    return None


def _read_tool_args(intent: str, slots: dict[str, Any]) -> dict[str, Any]:
    if intent == "query_events":
        args: dict[str, Any] = {"status": slots.get("status", "all")}
        if slots.get("productId") is not None:
            args["productId"] = slots["productId"]
        if slots.get("page") is not None:
            args["page"] = slots["page"]
        return args
    if intent == "query_stats":
        return {"eventId": slots["eventId"]} if slots.get("eventId") else {}
    if intent == "diagnose":
        return {"eventId": slots["eventId"]} if slots.get("eventId") else {}
    return {}


async def router_node(state: dict, config: RunnableConfig) -> dict:
    messages = state.get("messages") or []
    text = _last_user_text(messages)
    now = datetime.now(TZ).isoformat(timespec="seconds")

    decision: RouterDecision | None = None
    try:
        decision = await _classify(build_prompt(messages, now))
    except Exception:  # 分类失败也要给用户一句人话
        logger.exception("router 调用失败")

    intent = decision.intent if decision else "unknown"
    slots = normalize_slots(intent, decision.slots) if decision else {}

    if intent == "unknown":
        guess = guess_decision(text)
        if guess is None:
            logger.info("router：%r → unknown", text[:40])
            return {"intent": "unknown", "slots": {}, "missing": []}
        logger.info("router：模型没给出有效意图，关键词兜底 %r → %s", text[:40], guess.intent)
        intent = guess.intent
        slots = normalize_slots(intent, guess.slots)

    missing = missing_slots(intent, slots)
    # 新一轮用户输入开始：清掉上一轮的结果，否则 respond 会读到旧 result/tool_result
    # （曾出现：上次写操作成功后问活动列表，回复的是写操作的 data → 被渲染成空列表）
    update: dict[str, Any] = {
        "intent": intent,
        "slots": slots,
        "missing": missing,
        "tool_result": None,
        "result": None,
        "reply": "",
        "pending_action": None,
        "approved": None,
        "feedback": "",
    }
    if intent in READ_INTENTS:
        update["tool_name"] = {
            "query_events": "query_seckill_events",
            "query_stats": "get_seckill_stats",
            "diagnose": "diagnose_stock",
        }[intent]
        # 缺必填槽位时会被路由到 clarify，参数不完整就不要硬拼
        update["tool_args"] = {} if missing else _read_tool_args(intent, slots)
    elif intent in WRITE_INTENTS:
        update["tool_name"] = (
            "create_seckill_event" if intent == "create_event" else "adjust_seckill_stock"
        )
    logger.info("router：%r → %s，slots=%s", text[:40], intent, slots)
    return update


def route_after_router(state: dict) -> str:
    intent = state.get("intent")
    if intent in WRITE_INTENTS:
        return "clarify" if state.get("missing") else "plan_write"
    if intent in READ_INTENTS:
        return "clarify" if state.get("missing") else "read"
    return "respond"
