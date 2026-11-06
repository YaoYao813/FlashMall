"""
M4 端到端验收：MCP 工具 + LangGraph 写链路（人工确认/幂等/审计/重启恢复）。

前置：
- docker compose up -d（含 flashmall-agent-redis，宿主机 6381）
- Java 五个服务与网关可用，agent-service/.env 已配置管理员账号
- 用 agent-service 的虚拟环境运行：

    cd agent-service && .venv/Scripts/python ../scripts/acceptance_m4.py

说明：
- 读接口走真实 MCP 工具；写链路跳过 router（用 aupdate_state 伪造 router 输出），
  这样验收不依赖 LLM，数字可复现。LLM 分类由 app.cli 手动验，不在此脚本。
- 脚本会创建一条商品 7 的活动并在结束时下线；会临时调整一条进行中活动的库存，
  结束时恢复原值。验收脚本本身可以直连 MySQL 校验审计表（Agent 不行）。
"""
import asyncio
import json
import subprocess
import sys
import time
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import uuid4

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "agent-service"))

from langgraph.checkpoint.redis.aio import AsyncRedisSaver
from langgraph.types import Command

from app.config import settings
from app.graph.builder import build_graph
from app.mcp_client import get_toolbox

TZ8 = timezone(timedelta(hours=8))
FMT = "%Y-%m-%dT%H:%M:%S"
GATEWAY = settings.flashmall_base_url
PRODUCT_ID = 7
CREATE_STOCK = 5
CREATE_PRICE = 9.90
ADJUST_DELTA = 2

PASS = []
FAIL = []


# ==================== 断言与工具 ====================

def check(name, cond, detail=""):
    mark = "PASS" if cond else "FAIL"
    (PASS if cond else FAIL).append(name)
    print(f"  [{mark}] {name}" + (f"：{detail}" if detail and not cond else ""))


def mysql(sql):
    out = subprocess.check_output(
        ["docker", "exec", "flashmall-mysql", "mysql", "--default-character-set=utf8mb4",
         "-uroot", "-proot123", "-N", "-B", "-e", sql],
        stderr=subprocess.DEVNULL,
    ).decode("utf-8", "replace")
    return out.strip()


def audit_count(tool_name=None):
    where = f" WHERE tool_name='{tool_name}'" if tool_name else ""
    return int(mysql(f"SELECT COUNT(*) FROM flashmall.admin_audit_log{where};") or 0)


def admin_request(method, path, body):
    payload = json.dumps({"username": settings.admin_username,
                          "password": settings.admin_password.get_secret_value()}).encode()
    req = urllib.request.Request(GATEWAY + "/api/auth/login", data=payload,
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=10) as resp:
        token = json.loads(resp.read())["data"]
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(GATEWAY + path, data=data, method=method,
                                 headers={"Content-Type": "application/json",
                                          "Authorization": "Bearer " + token})
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read())


def tool_ok(payload):
    return bool(payload.get("ok"))


async def seed_write(graph, thread_id, intent, slots):
    """跳过 router，把它的输出直接写进 checkpoint，再让图从条件边继续。"""
    tool_name = "adjust_seckill_stock" if intent == "adjust_stock" else "create_seckill_event"
    cfg = {"configurable": {"thread_id": thread_id}}
    await graph.aupdate_state(
        cfg,
        {"intent": intent, "slots": slots, "missing": [], "tool_name": tool_name},
        as_node="router",
    )
    return cfg, await graph.ainvoke(None, cfg)


async def pick_adjustable_event(toolbox):
    """挑一条已预热（Redis 库存可读）的进行中活动，increment 才能成功。"""
    listing = await toolbox.call_tool("query_seckill_events", {"status": "running"})
    for item in (listing.get("data") or {}).get("items") or []:
        if item.get("id") is None:
            continue
        stats = await toolbox.call_tool("get_seckill_stats", {"eventId": item["id"]})
        stock = (stats.get("data") or {}).get("stock") or {}
        if stock.get("redisStock") is not None:
            return item
    return None


def cleanup_product_events(product_id):
    """把商品 7 之前残留的未结束活动下线，保证脚本可重复运行。"""
    resp = admin_request("GET", f"/api/admin/seckill/events?status=all&productId={product_id}&page=1&size=20", None)
    now = datetime.now(TZ8).strftime(FMT)
    for item in (resp.get("data") or {}).get("records") or []:
        if item.get("status") in ("pending", "running"):
            admin_request("PUT", f"/api/admin/seckill/events/{item['id']}", {"endTime": now})


# ==================== 主流程 ====================

async def main():
    toolbox = get_toolbox()
    await toolbox.start()
    try:
        async with AsyncRedisSaver(redis_url=settings.redis_url) as saver:
            graph = build_graph(saver)
            await run_checks(graph, toolbox)
    finally:
        await toolbox.stop()


async def run_checks(graph, toolbox):
    print("\n### 1. 读工具（真实 MCP + 网关）")
    listing = await toolbox.call_tool("query_seckill_events", {"status": "all", "page": 1})
    check("query_seckill_events 返回 ok", tool_ok(listing))
    items = (listing.get("data") or {}).get("items") or []
    check("活动列表非空", len(items) > 0, f"items={len(items)}")

    event = await pick_adjustable_event(toolbox)
    check("存在可调整的进行中活动", event is not None)
    if event is None:
        return
    event_id = event["id"]

    stats = await toolbox.call_tool("get_seckill_stats", {"eventId": event_id})
    check("get_seckill_stats 带中文结论",
          "已售" in ((stats.get("data") or {}).get("conclusion") or ""))

    diagnose = await toolbox.call_tool("diagnose_stock", {"eventId": event_id})
    check("diagnose_stock 返回检查结果",
          tool_ok(diagnose) and "checked" in (diagnose.get("data") or {}))

    print("\n### 2. 创建活动：幂等回放 + 审计")
    cleanup_product_events(PRODUCT_ID)
    key = f"accept-m4:{uuid4().hex}"
    start = (datetime.now(TZ8) + timedelta(days=1)).strftime(FMT)
    end = (datetime.now(TZ8) + timedelta(days=1, hours=2)).strftime(FMT)
    create_args = {"productId": PRODUCT_ID, "seckillPrice": CREATE_PRICE,
                   "stock": CREATE_STOCK, "startTime": start, "endTime": end,
                   "idempotencyKey": key}
    created = await toolbox.call_tool("create_seckill_event", create_args)
    check("create_seckill_event 成功", tool_ok(created),
          (created.get("error") or {}).get("message", ""))
    data = created.get("data") or {}
    new_event_id = (data.get("event") or {}).get("id")
    check("返回活动 id", new_event_id is not None)
    check("预热完成", (data.get("preheat") or {}).get("redisStock") == CREATE_STOCK)

    before_count = int(mysql(
        f"SELECT COUNT(*) FROM flashmall.seckill_event WHERE product_id={PRODUCT_ID};"))
    replay = await toolbox.call_tool("create_seckill_event", create_args)
    after_count = int(mysql(
        f"SELECT COUNT(*) FROM flashmall.seckill_event WHERE product_id={PRODUCT_ID};"))
    replayed_event = (replay.get("data") or {}).get("event") or {}
    check("同键回放 replayed=true", replayed_event.get("replayed") is True)
    check("回放不新增活动", before_count == after_count,
          f"{before_count} → {after_count}")
    check("创建审计只有 1 条", audit_count("create_seckill_event") >= 1
          and mysql(f"SELECT COUNT(*) FROM flashmall.admin_audit_log "
                    f"WHERE idempotency_key='{key}';") == "1")

    print("\n### 3. 调整库存：拒绝不落库")
    stock_before = ((await toolbox.call_tool(
        "get_seckill_stats", {"eventId": event_id})).get("data") or {}).get("stock") or {}
    redis_before = stock_before.get("redisStock")
    audit_before = audit_count("adjust_seckill_stock")
    cfg, interrupted = await seed_write(
        graph, f"accept-reject-{uuid4().hex[:6]}", "adjust_stock",
        {"eventId": event_id, "delta": ADJUST_DELTA, "reason": "验收：拒绝分支"})
    check("写操作触发 interrupt", "__interrupt__" in interrupted)
    rejected = await graph.ainvoke(
        Command(resume={"approved": False, "feedback": ""}), cfg)
    check("拒绝回复", "拒绝" in (rejected.get("reply") or ""))
    check("拒绝不写审计", audit_count("adjust_seckill_stock") == audit_before)
    redis_after_reject = ((await toolbox.call_tool(
        "get_seckill_stats", {"eventId": event_id})).get("data") or {}).get("stock") or {}
    check("拒绝不改库存", redis_after_reject.get("redisStock") == redis_before)

    print("\n### 4. 调整库存：批准 + 幂等键固定")
    thread = f"accept-approve-{uuid4().hex[:6]}"
    audit_before = audit_count("adjust_seckill_stock")
    cfg, interrupted = await seed_write(
        graph, thread, "adjust_stock",
        {"eventId": event_id, "delta": ADJUST_DELTA, "reason": "验收：批准分支"})
    state = await graph.aget_state(cfg)
    idem_key = state.values["pending_action"]["idempotency_key"]
    approved = await graph.ainvoke(
        Command(resume={"approved": True, "feedback": ""}), cfg)
    check("批准后执行成功", "已增加" in (approved.get("reply") or ""))
    check("审计 +1", audit_count("adjust_seckill_stock") == audit_before + 1)

    stock_after = ((await toolbox.call_tool(
        "get_seckill_stats", {"eventId": event_id})).get("data") or {}).get("stock") or {}
    check("Redis 库存 +delta",
          stock_after.get("redisStock") == redis_before + ADJUST_DELTA,
          f"{redis_before} → {stock_after.get('redisStock')}")

    replay_adjust = await toolbox.call_tool(
        "adjust_seckill_stock", {"eventId": event_id, "delta": ADJUST_DELTA,
                                 "reason": "验收：批准分支", "idempotencyKey": idem_key})
    stock_replay = ((await toolbox.call_tool(
        "get_seckill_stats", {"eventId": event_id})).get("data") or {}).get("stock") or {}
    check("同键回放 replayed=true",
          (replay_adjust.get("data") or {}).get("replayed") is True)
    check("回放不重复加减",
          stock_replay.get("redisStock") == stock_after.get("redisStock"))

    print("\n### 5. 进程重启后恢复挂起确认")
    restart_thread = f"accept-restart-{uuid4().hex[:6]}"
    cfg, interrupted = await seed_write(
        graph, restart_thread, "adjust_stock",
        {"eventId": event_id, "delta": 1, "reason": "验收：重启恢复"})
    check("重启前处于挂起", "__interrupt__" in interrupted)
    saved_key = (await graph.aget_state(cfg)).values["pending_action"]["idempotency_key"]

    async with AsyncRedisSaver(redis_url=settings.redis_url) as saver2:
        graph2 = build_graph(saver2)
        snapshot = await graph2.aget_state(cfg)
        check("新实例能看到挂起卡片", bool(snapshot.interrupts))
        resumed = await graph2.ainvoke(
            Command(resume={"approved": True, "feedback": ""}), cfg)
        check("重启后恢复执行成功", "已增加 1 件" in (resumed.get("reply") or ""))
        check("恢复后幂等键不变",
              (await graph2.aget_state(cfg)).values["pending_action"]["idempotency_key"]
              == saved_key)

    print("\n### 6. 还原夹具")
    for delta, reason in ((-ADJUST_DELTA, "验收：还原库存"), (-1, "验收：还原重启测试")):
        cfg, interrupted = await seed_write(
            graph, f"accept-restore-{uuid4().hex[:6]}", "adjust_stock",
            {"eventId": event_id, "delta": delta, "reason": reason})
        await graph.ainvoke(Command(resume={"approved": True, "feedback": ""}), cfg)
    final = ((await toolbox.call_tool(
        "get_seckill_stats", {"eventId": event_id})).get("data") or {}).get("stock") or {}
    check("库存已还原", final.get("redisStock") == redis_before,
          f"{final.get('redisStock')} != {redis_before}")

    if new_event_id is not None:
        resp = admin_request("PUT", f"/api/admin/seckill/events/{new_event_id}",
                             {"endTime": datetime.now(TZ8).strftime(FMT)})
        check("清理：下线验收活动", resp.get("code") == 200)


if __name__ == "__main__":
    started = time.time()
    try:
        asyncio.run(main())
    except Exception as exc:  # noqa: BLE001 - 验收脚本要给出汇总而不是堆栈收尾
        FAIL.append("脚本异常")
        print(f"\n脚本异常：{type(exc).__name__}: {exc}")
    finally:
        print(f"\n=== 结果：{len(PASS)} 通过 / {len(FAIL)} 失败，"
              f"耗时 {time.time() - started:.1f}s ===")
        sys.exit(0 if not FAIL else 1)
