"""M5 评测：25 条用例 × 3 次，只测决策层（意图 / 工具 / 参数 / 该不该追问）。

为什么用桩工具箱：真后端的状态会变（活动过期、库存被抢、同一活动建第二次被拒），
同一份用例跑两次可能一个成功一个失败，数字就不可复现。桩只负责给读工具返回固定形状的数据；
写工具根本不会被调用——图在确认卡片处挂起，评测只判「它有没有把卡片正确地拼出来」。

用法（在 agent-service 目录下）：
    python -m evals.run_eval                    # 开发集 20 条 × 3 次
    python -m evals.run_eval --split holdout    # 留存集 5 条（提示词定稿后再跑）
    python -m evals.run_eval --split all --runs 1   # 全量冒烟
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import statistics
import subprocess
import time
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path
from typing import Any
from uuid import uuid4

from langchain_core.messages import AIMessage, HumanMessage
from langgraph.checkpoint.memory import InMemorySaver

import app.graph.nodes.read as read_mod
import app.graph.nodes.write as write_mod
from app.config import settings
from app.graph.builder import build_graph

CASES_PATH = Path(__file__).with_name("cases.json")
WRITE_TOOLS = {"create_seckill_event", "adjust_seckill_stock"}
CARD_INTERNAL_TOOL = "get_seckill_stats"  # plan_write 拼确认卡片时会读一次当前库存，不算业务动作
CATEGORY_LABELS = {
    "tool_selection": "工具选择",
    "slot_extraction": "参数抽取",
    "clarify": "该追问",
}


class StubToolbox:
    """评测专用工具箱：返回固定形状的数据，所有调用都记账。"""

    def __init__(self) -> None:
        self.calls: list[tuple[str, dict[str, Any]]] = []

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
        self.calls.append((name, dict(arguments)))
        if name == "query_seckill_events":
            return {
                "ok": True,
                "data": {
                    "items": [],
                    "total": 0,
                    "pages": 0,
                    "message": "没有符合条件的活动。",
                },
            }
        if name == "get_seckill_stats":
            return {
                "ok": True,
                "data": {
                    "eventId": arguments.get("eventId"),
                    "stock": {"redisStock": 10},
                    "conclusion": "评测桩：活动已支付 1 单。",
                },
            }
        if name == "diagnose_stock":
            return {"ok": True, "data": {"message": "评测桩：库存一致。"}}
        return {"ok": True, "data": {"conclusion": "评测桩：写工具不应在评测中被执行"}}


def _as_datetime(value: Any) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        return datetime.fromisoformat(value.strip().replace(" ", "T"))
    except ValueError:
        return None


def match_field(key: str, expected: Any, actual: Any) -> bool:
    """参数比较的宽松规则：时间按 datetime 比，金额允许 299.9 / 299.90，原因允许措辞差异。"""
    if key in ("startTime", "endTime"):
        want, got = _as_datetime(expected), _as_datetime(actual)
        return want is not None and want == got
    if key == "reason":
        return str(expected) in str(actual) or str(actual) in str(expected)
    if isinstance(expected, (int, float)) and isinstance(actual, (int, float)):
        return abs(float(expected) - float(actual)) < 1e-6
    return expected == actual


def observe(values: dict[str, Any], toolbox: StubToolbox) -> dict[str, Any]:
    pending = values.get("pending_action") or {}
    # 只有写分支拼确认卡片时会内部读一次 stats；读分支的 stats 调用是正主，不能排除
    business_calls = [
        name
        for name, _ in toolbox.calls
        if not (pending and name == CARD_INTERNAL_TOOL)
    ]
    if pending:  # 写操作：参数以确认卡片里的为准
        tool = pending.get("tool_name")
        args = pending.get("tool_args") or {}
    else:
        tool = values.get("tool_name")
        args = values.get("tool_args") or {}
    return {
        "intent": values.get("intent"),
        "slots": values.get("slots") or {},
        "missing": values.get("missing") or [],
        "router_source": values.get("router_source"),
        "tool": tool,
        "args": args,
        "interrupted": bool(pending),
        "called": business_calls,
        "reply": values.get("reply") or "",
        "acted": bool(pending) or bool(business_calls),
    }


def grade(case: dict[str, Any], obs: dict[str, Any]) -> tuple[bool, list[str]]:
    """返回 (是否通过, 没对上的参数名)。"""
    expect = case["expect"]

    if case["category"] == "clarify":
        # 该追问的三条：不挂起确认卡片、不调用任何业务工具、回复里点名缺了什么
        ok = (
            not obs["acted"]
            and all(text in obs["reply"] for text in expect.get("reply_contains", []))
        )
        return ok, []

    if expect.get("read_only"):
        # 不许动手：读工具随便调，但不能挂起确认卡片、不能调用任何写工具
        acted = obs["interrupted"] or any(name in WRITE_TOOLS for name in obs["called"])
        return not acted, []

    if "tool" in expect:
        want = expect["tool"]
        if want is None:
            # 不该动手：既没挂起卡片，也没调工具
            if obs["acted"] or obs["tool"] not in (None, ""):
                return False, []
        else:
            executed = want in obs["called"] or (obs["interrupted"] and want in WRITE_TOOLS)
            if obs["tool"] != want or not executed:
                return False, []

    mismatched = [
        key
        for key, value in (expect.get("args") or {}).items()
        if not match_field(key, value, obs["args"].get(key))
    ]
    return not mismatched, mismatched


def signature(obs: dict[str, Any]) -> str:
    """跨 run 一致率按「行为」比，不按最终文字比。"""
    return json.dumps(
        {
            "tool": obs["tool"],
            "args": {k: str(v) for k, v in sorted(obs["args"].items())},
            "interrupted": obs["interrupted"],
        },
        ensure_ascii=False,
        sort_keys=True,
    )


def git_commit() -> str:
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=Path(__file__).resolve().parents[1],
            capture_output=True,
            text=True,
            check=True,
        )
        return out.stdout.strip()
    except (subprocess.CalledProcessError, OSError):
        return "unknown"


async def run_case(graph, case: dict[str, Any], run_index: int) -> dict[str, Any]:
    toolbox = StubToolbox()
    # read/write 节点里是 from ... import get_toolbox，替换模块属性才生效
    read_mod.get_toolbox = lambda: toolbox
    write_mod.get_toolbox = lambda: toolbox

    messages = []
    for turn in case.get("history") or []:
        if turn["role"] == "user":
            messages.append(HumanMessage(content=turn["text"]))
        else:
            messages.append(AIMessage(content=turn["text"]))
    messages.append(HumanMessage(content=case["message"]))

    config = {"configurable": {"thread_id": f"eval-{case['id']}-{run_index}-{uuid4().hex[:8]}"}}
    started = time.perf_counter()
    try:
        await graph.ainvoke({"messages": messages}, config)
        snapshot = await graph.aget_state(config)
        obs = observe(snapshot.values, toolbox)
        obs["error"] = None
    except Exception as exc:  # noqa: BLE001 —— 评测要把异常记成失败，不能让整轮中断
        obs = {
            "intent": None,
            "slots": {},
            "missing": [],
            "router_source": None,
            "tool": None,
            "args": {},
            "interrupted": False,
            "called": [],
            "reply": "",
            "acted": False,
            "error": f"{type(exc).__name__}: {exc}",
        }
    obs["elapsed_ms"] = int((time.perf_counter() - started) * 1000)
    return obs


def _pct(value: float) -> str:
    return f"{value * 100:.1f}%"


def summarize(cases, results, split: str, runs: int) -> str:
    by_category: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for case in cases:
        by_category[case["category"]].append(case)

    total_runs = len(cases) * runs
    passed_runs = 0
    consistent = 0
    unsafe_runs = unsafe_total = 0
    field_hit = field_total = 0
    sources: Counter[str] = Counter()
    latencies: list[int] = []
    failures: list[str] = []

    for case in cases:
        observations = results[case["id"]]
        flags = []
        for obs in observations:
            ok, mismatched = grade(case, obs)
            flags.append(ok)
            passed_runs += ok
            latencies.append(obs["elapsed_ms"])
            sources[str(obs["router_source"])] += 1

            if case["category"] == "clarify":
                unsafe_total += 1
                # 误动作 = 该追问却挂起了确认卡片或调用写工具；调只读工具只是判错，不是安全事故
                unsafe_runs += bool(obs["interrupted"]) or any(
                    name in WRITE_TOOLS for name in obs["called"]
                )

            if case["category"] == "slot_extraction" and case.get("expect", {}).get("args"):
                field_total += len(case["expect"]["args"])
                field_hit += len(case["expect"]["args"]) - len(mismatched)

            if not ok:
                failures.append(_format_failure(case, obs, mismatched))

        if len({signature(obs) for obs in observations}) == 1:
            consistent += 1

    lines: list[str] = []
    add = lines.append
    add(f"# M5 评测报告（{split}）")
    add("")
    add(f"- 运行时间：{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    add(f"- 模型：`{settings.llm_model}`，温度 0")
    add(f"- git commit：`{git_commit()}`")
    add(f"- 用例集：`{CASES_PATH.name}` sha256 `{_sha256(CASES_PATH)}`")
    add(f"- 规模：{len(cases)} 条 × {runs} 次 = {total_runs} 次调用")
    add("")
    add("## 总体")
    add("")
    add("| 指标 | 值 |")
    add("|---|---|")
    add(f"| 准确率（按 run 计） | {_pct(passed_runs / total_runs)} |")
    add(f"| 跨 run 一致率 | {_pct(consistent / len(cases))} |")
    if field_total:
        add(f"| 字段级准确率（参数抽取） | {_pct(field_hit / field_total)} |")
    add(f"| 误动作率（该追问却动手） | {_pct(unsafe_runs / unsafe_total if unsafe_total else 0)} |")
    add(f"| P50 / P95 单轮耗时 | {round(statistics.median(latencies))} ms / "
        f"{_percentile(latencies, 95)} ms |")
    add("| router 来源分布 | " + "，".join(f"{k} {v}" for k, v in sources.most_common()))
    add("")
    add("## 分类")
    add("")
    add("| 类别 | 条数 | 通过 run | 准确率 |")
    add("|---|---|---|---|")
    for category, items in by_category.items():
        ok = sum(
            1
            for case in items
            for obs in results[case["id"]]
            if grade(case, obs)[0]
        )
        total = len(items) * runs
        add(f"| {CATEGORY_LABELS.get(category, category)} | {len(items)} | {ok}/{total} | "
            f"{_pct(ok / total)} |")
    add("")
    add("## 失败明细")
    add("")
    if failures:
        add("\n\n".join(failures))
    else:
        add("无。")
    add("")
    return "\n".join(lines)


def _format_failure(case: dict[str, Any], obs: dict[str, Any], mismatched: list[str]) -> str:
    expect = case["expect"]
    lines = [f"### {case['id']} · {case['message']}"]
    if case.get("history"):
        history = " / ".join(f"{t['role']}: {t['text'][:40]}" for t in case["history"])
        lines.append(f"- 历史：{history}")
    lines.append(f"- 期望：{json.dumps(expect, ensure_ascii=False)}")
    got = {
        "intent": obs["intent"],
        "router_source": obs["router_source"],
        "tool": obs["tool"],
        "args": obs["args"],
        "acted": obs["acted"],
        "reply": obs["reply"][:120],
    }
    if obs.get("error"):
        got["error"] = obs["error"]
    lines.append(f"- 实际：{json.dumps(got, ensure_ascii=False, default=str)}")
    if mismatched:
        lines.append(f"- 参数不符：{'、'.join(mismatched)}")
    lines.append("")
    return "\n".join(lines)


def _percentile(values: list[int], pct: int) -> int:
    ordered = sorted(values)
    index = min(len(ordered) - 1, round((pct / 100) * (len(ordered) - 1)))
    return ordered[index]


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()[:12]


async def main_async(args) -> None:
    payload = json.loads(CASES_PATH.read_text(encoding="utf-8"))
    cases = [
        case
        for case in payload["cases"]
        if args.split == "all" or case.get("split", "dev") == args.split
    ]
    if not cases:
        raise SystemExit(f"没有匹配的用例（split={args.split}）")

    graph = build_graph(InMemorySaver())
    results: dict[str, list[dict[str, Any]]] = {}
    for index, case in enumerate(cases, start=1):
        results[case["id"]] = []
        for run in range(args.runs):
            results[case["id"]].append(await run_case(graph, case, run))
        flags = [grade(case, obs)[0] for obs in results[case["id"]]]
        # 用 ASCII 标记：Windows 控制台默认 GBK，打 ✓/✗ 会炸
        mark = "".join("o" if ok else "x" for ok in flags)
        print(f"[{index:>2}/{len(cases)}] {case['id']:<8} {mark}", flush=True)

    report = summarize(cases, results, args.split, args.runs)
    out_path = Path(args.out) if args.out else Path(__file__).with_name(f"report-{args.split}.md")
    out_path.write_text(report, encoding="utf-8")
    print()
    print(report)
    print(f"报告已写入 {out_path}")


def main() -> None:
    parser = argparse.ArgumentParser(description="M5 决策层评测")
    parser.add_argument("--split", choices=["dev", "holdout", "all"], default="dev")
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--out", default="")
    args = parser.parse_args()
    asyncio.run(main_async(args))


if __name__ == "__main__":
    main()
