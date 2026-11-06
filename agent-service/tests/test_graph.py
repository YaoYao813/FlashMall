"""图行为测试：router 用假分类器，MCP 用假工具箱，不依赖网络/LLM/Redis。

重点覆盖：读分支、缺槽追问、确认卡片、拒绝不调工具、批准带幂等键。
"""

import pytest
from langchain_core.messages import AIMessage, HumanMessage
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.types import Command

import app.graph.nodes.read as read_mod
import app.graph.nodes.router as router_mod
import app.graph.nodes.write as write_mod
from app.graph.builder import build_graph
from app.graph.schemas import RouterDecision


class FakeToolbox:
    def __init__(self, responses):
        self.calls = []
        self.responses = responses

    async def call_tool(self, name, arguments):
        self.calls.append((name, dict(arguments)))
        response = self.responses[name]
        return response(arguments) if callable(response) else response


@pytest.fixture
def wire(monkeypatch):
    def _wire(decision, responses):
        # 传列表表示按调用顺序依次返回（多轮对话）
        sequence = list(decision) if isinstance(decision, (list, tuple)) else [decision]

        async def fake_classify(prompt):
            return sequence.pop(0) if len(sequence) > 1 else sequence[0]

        monkeypatch.setattr("app.graph.nodes.router._classify", fake_classify)
        toolbox = FakeToolbox(responses)
        monkeypatch.setattr(read_mod, "get_toolbox", lambda: toolbox)
        monkeypatch.setattr(write_mod, "get_toolbox", lambda: toolbox)
        graph = build_graph(InMemorySaver())
        return graph, toolbox

    return _wire


async def test_query_events_renders_list(wire):
    graph, toolbox = wire(
        RouterDecision(intent="query_events", slots={"status": "running"}),
        {
            "query_seckill_events": {
                "ok": True,
                "data": {
                    "items": [
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
                    ],
                    "total": 1,
                    "pages": 1,
                },
            }
        },
    )
    config = {"configurable": {"thread_id": "t-read"}}
    result = await graph.ainvoke({"messages": [HumanMessage("有哪些进行中的活动")]}, config)

    assert "共 1 个活动" in result["reply"]
    assert toolbox.calls == [("query_seckill_events", {"status": "running"})]


async def test_missing_event_id_asks_instead_of_calling_tool(wire):
    graph, toolbox = wire(
        RouterDecision(intent="query_stats", slots={}),
        {},
    )
    result = await graph.ainvoke(
        {"messages": [HumanMessage("卖得怎么样")]},
        {"configurable": {"thread_id": "t-clarify-stats"}},
    )

    assert "活动 id" in result["reply"]
    assert toolbox.calls == []


async def test_missing_reason_asks_before_planning(wire):
    graph, toolbox = wire(
        RouterDecision(intent="adjust_stock", slots={"eventId": 9, "delta": 5}),
        {},
    )
    result = await graph.ainvoke(
        {"messages": [HumanMessage("活动 9 加 5 件")]},
        {"configurable": {"thread_id": "t-clarify-adjust"}},
    )

    assert "调整原因" in result["reply"]
    assert toolbox.calls == []


async def test_write_interrupts_and_rejection_calls_nothing(wire):
    graph, toolbox = wire(
        RouterDecision(
            intent="create_event",
            slots={
                "productId": 7,
                "seckillPrice": 199,
                "stock": 50,
                "startTime": "2026-11-11T10:00:00",
                "endTime": "2026-11-11T12:00:00",
            },
        ),
        {},
    )
    config = {"configurable": {"thread_id": "t-reject"}}
    result = await graph.ainvoke({"messages": [HumanMessage("给商品 7 建活动")]}, config)

    assert "__interrupt__" in result
    card = result["__interrupt__"][0].value
    assert card["action"] == "create_event"
    assert any(field["label"] == "秒杀价" for field in card["fields"])

    state = await graph.aget_state(config)
    key = state.values["pending_action"]["idempotency_key"]
    assert key.startswith("t-reject:")

    result = await graph.ainvoke(Command(resume={"approved": False}), config)
    assert "拒绝" in result["reply"]
    assert toolbox.calls == []  # 拒绝不产生任何工具调用


async def test_approved_adjust_sends_same_idempotency_key(wire):
    graph, toolbox = wire(
        RouterDecision(
            intent="adjust_stock",
            slots={"eventId": 9, "delta": 5, "reason": "热度高"},
        ),
        {
            "get_seckill_stats": {"ok": True, "data": {"stock": {"redisStock": 10}}},
            "adjust_seckill_stock": {
                "ok": True,
                "data": {
                    "dbStock": 55,
                    "redisStock": 15,
                    "replayed": False,
                    "conclusion": "活动 9 库存已增加 5 件：活动库存 55，Redis 剩余 15。",
                },
            },
        },
    )
    config = {"configurable": {"thread_id": "t-approve"}}
    result = await graph.ainvoke({"messages": [HumanMessage("活动 9 加 5 件")]}, config)

    card = result["__interrupt__"][0].value
    assert any(field["value"] == "10 → 15" for field in card["fields"])

    state = await graph.aget_state(config)
    key = state.values["pending_action"]["idempotency_key"]

    result = await graph.ainvoke(Command(resume={"approved": True}), config)
    assert "已增加 5 件" in result["reply"]

    calls = {name: args for name, args in toolbox.calls}
    assert calls["adjust_seckill_stock"]["idempotencyKey"] == key
    assert calls["adjust_seckill_stock"]["delta"] == 5


async def test_read_after_write_does_not_reuse_stale_result(wire):
    """回归：写操作成功后再查列表，不能把上一轮的 result 当成本轮结果渲染成空。"""
    graph, _toolbox = wire(
        [
            RouterDecision(
                intent="adjust_stock",
                slots={"eventId": 9, "delta": 2, "reason": "热度高"},
            ),
            RouterDecision(intent="query_events", slots={"status": "running"}),
        ],
        {
            "get_seckill_stats": {"ok": True, "data": {"stock": {"redisStock": 10}}},
            "adjust_seckill_stock": {
                "ok": True,
                "data": {"dbStock": 52, "redisStock": 12, "replayed": False,
                         "conclusion": "活动 9 库存已增加 2 件。"},
            },
            "query_seckill_events": {
                "ok": True,
                "data": {
                    "items": [{"id": 9, "productId": 7, "seckillPrice": 1.0,
                               "stock": 52, "startTime": "2026-11-05T10:00:00",
                               "endTime": "2026-11-05T12:00:00", "status": "running"}],
                    "total": 1,
                    "pages": 1,
                },
            },
        },
    )
    config = {"configurable": {"thread_id": "t-multi"}}
    await graph.ainvoke({"messages": [HumanMessage("活动 9 加 2 件")]}, config)
    await graph.ainvoke(Command(resume={"approved": True}), config)

    second = await graph.ainvoke({"messages": [HumanMessage("有哪些进行中的活动")]}, config)
    assert "共 1 个活动" in second["reply"]
    assert "没有符合条件" not in second["reply"]


def test_build_prompt_folds_history_into_one_user_message():
    """历史必须压成一条 user 消息，否则模型会接着写上一轮的回答而不输出 JSON。"""
    messages = [
        HumanMessage("有哪些进行中的活动"),
        AIMessage("共 5 个活动：" + "长" * 300),
        HumanMessage("活动 32 加 2 件"),
    ]
    prompt = router_mod.build_prompt(messages, "2026-11-05T10:00:00")

    assert len(prompt) == 2
    assert isinstance(prompt[1], HumanMessage)  # 只有一条 user 消息，不按角色喂
    body = prompt[1].content
    assert "当前用户消息：活动 32 加 2 件" in body
    assert "助手：" in body and "用户：有哪些进行中的活动" in body
    assert "长" * 100 not in body  # 助手的冗长回答被截断


@pytest.mark.parametrize(
    ("text", "intent"),
    [
        ("有哪些进行的活动？", "query_events"),
        ("查活动列表", "query_events"),
        ("活动经营数据", "query_stats"),
        ("看活动经营数据", "query_stats"),
        ("库存对不对", "diagnose"),
        ("帮我建个活动，商品 7", "create_event"),
        ("活动 32 加 2 件，理由：页面测试", "adjust_stock"),
        ("今天天气怎么样", None),
        ("帮我删掉活动 32", None),
    ],
)
def test_guess_decision_keywords(text, intent):
    decision = router_mod.guess_decision(text)
    assert (decision.intent if decision else None) == intent


async def test_router_falls_back_when_model_says_unknown(monkeypatch):
    async def fake_classify(prompt):
        return RouterDecision(intent="unknown", slots={})

    monkeypatch.setattr(router_mod, "_classify", fake_classify)
    update = await router_mod.router_node(
        {"messages": [HumanMessage("有哪些进行的活动？")]}, {}
    )

    assert update["intent"] == "query_events"
    assert update["tool_name"] == "query_seckill_events"
    assert update["tool_args"] == {"status": "running"}


async def test_router_survives_classifier_exception(monkeypatch):
    async def boom(prompt):
        raise RuntimeError("HTTP 429")

    monkeypatch.setattr(router_mod, "_classify", boom)
    update = await router_mod.router_node({"messages": [HumanMessage("库存对不对")]}, {})

    assert update["intent"] == "diagnose"
    assert update["tool_name"] == "diagnose_stock"
