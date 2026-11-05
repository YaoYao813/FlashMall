# -*- coding: utf-8 -*-
"""
M3 端到端验收：GET /api/admin/seckill/events/{id}/stats。

前置：docker compose up -d，网关 8888、seckill-service 8080、order-service 8083 可用。
运行：
    PYTHONIOENCODING=utf-8 python scripts/acceptance_m3.py

说明：
- 管理接口走网关 8888（真实 JWT）；秒杀直连 8080、支付/取消直连 8083 并伪造 X-User-Id，
  这是为了用多个不同用户制造订单，不经过网关登录，不影响被测逻辑。
- 会重置商品 3 的 product_stock 为 10，属于测试夹具，运行前请知悉。
"""
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

GATEWAY = "http://localhost:8888"
SECKILL = "http://localhost:8080"
ORDER = "http://localhost:8083"
TZ8 = timezone(timedelta(hours=8))
FMT = "%Y-%m-%dT%H:%M:%S"
PRODUCT_ID = 3
STOCK = 10
PRICE = 9.90

PASS = []
FAIL = []
TOKEN = None


def http(method, url, body=None, headers=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    h = {"Content-Type": "application/json"}
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8", "replace"))


def admin(method, path, body=None, idem_key=None):
    headers = {"Authorization": "Bearer " + TOKEN}
    if idem_key:
        headers["Idempotency-Key"] = idem_key
    status, payload = http(method, GATEWAY + path, body, headers)
    if status != 200:
        raise AssertionError("HTTP %s: %s" % (status, payload))
    return payload


def service_post(url, user_id=None):
    headers = {"X-User-Id": str(user_id)} if user_id is not None else {}
    status, payload = http("POST", url, None, headers)
    if status != 200:
        raise AssertionError("HTTP %s: %s" % (status, payload))
    return payload


def seckill(user_id):
    url = "%s/api/seckill?productId=%d" % (SECKILL, PRODUCT_ID)
    status, payload = http("POST", url, None, {"X-User-Id": str(user_id)})
    if status != 200:
        raise AssertionError("HTTP %s: %s" % (status, payload))
    return payload


def docker(*args):
    result = subprocess.run(["docker"] + list(args), capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip())
    return result.stdout.strip()


def redis_get(key):
    return docker("exec", "flashmall-redis", "redis-cli", "GET", key)


def redis_del(key):
    docker("exec", "flashmall-redis", "redis-cli", "DEL", key)


def mysql(sql):
    return docker("exec", "flashmall-mysql", "mysql", "-uroot", "-proot123",
                  "-N", "-B", "-e", sql)


def check(name, cond, detail=""):
    if cond:
        PASS.append(name)
        print("  [PASS] %s" % name)
    else:
        FAIL.append(name)
        print("  [FAIL] %s  %s" % (name, detail))


def downline_unfinished(product_id):
    for status in ("pending", "running"):
        resp = admin("GET", "/api/admin/seckill/events?status=%s&productId=%d&size=20"
                     % (status, product_id))
        for record in (resp.get("data") or {}).get("records") or []:
            admin("PUT", "/api/admin/seckill/events/%d" % record["id"],
                  {"endTime": (datetime.now() - timedelta(seconds=2)).strftime(FMT)})


def wait_until(predicate, timeout=25, interval=0.5):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if predicate():
            return True
        time.sleep(interval)
    return False


def main():
    global TOKEN

    print("### 0. 登录与夹具清理")
    status, login = http("POST", GATEWAY + "/api/auth/login",
                         {"username": "testbuyer", "password": "123456"})
    if status != 200 or login.get("code") != 200:
        raise SystemExit("登录失败，确认 docker compose 已启动：%s" % login)
    TOKEN = login["data"]

    downline_unfinished(PRODUCT_ID)
    redis_del("seckill:event:%d" % PRODUCT_ID)
    redis_del("seckill:stock:%d" % PRODUCT_ID)
    mysql("USE flashmall; UPDATE product_stock SET stock=%d, version=0 WHERE product_id=%d;"
          % (STOCK, PRODUCT_ID))
    order_since = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print("  已清理商品 %d 的未结束活动，重置 product_stock=%d" % (PRODUCT_ID, STOCK))

    print("\n### 1. 建活动并预热")
    start = datetime.now() + timedelta(seconds=8)
    end = start + timedelta(hours=1)
    key = "m3-accept-%d" % int(time.time())
    created = admin("POST", "/api/admin/seckill/events", {
        "productId": PRODUCT_ID,
        "seckillPrice": PRICE,
        "stock": STOCK,
        "startTime": start.strftime(FMT),
        "endTime": end.strftime(FMT),
    }, idem_key=key)
    event = created.get("data") or {}
    event_id = event.get("id")
    check("创建活动成功", created.get("code") == 200 and event.get("status") == "pending", created)

    reset = admin("POST", "/api/admin/seckill/events/%d/stock-sync" % event_id, {"mode": "reset"})
    check("预热成功", reset.get("code") == 200, reset)

    wait = max(0, (start - datetime.now()).total_seconds()) + 1.5
    print("  等待 %.1f 秒到活动开始..." % wait)
    time.sleep(wait)

    print("\n### 2. 三个用户各抢一单")
    base_user = 3_000_000 + (int(time.time()) % 1_000_000) * 100
    users = [base_user + i for i in (1, 2, 3)]
    results = [seckill(u) for u in users]
    check("三人秒杀全部成功", all(r.get("code") == 200 for r in results), results)

    print("  等待 MQ 消费者落库...")
    orders = []
    deadline = time.time() + 25
    while time.time() < deadline:
        orders = fetch_orders(order_since)
        if len(orders) == 3:
            break
        time.sleep(0.5)
    check("订单恰好 3 条", len(orders) == 3, orders)
    if len(orders) != 3:
        raise SystemExit("订单数量不对，后续统计无意义")

    print("\n### 3. 一笔支付、一笔取消、一笔待支付")
    paid_order, cancelled_order, pending_order = orders[0], orders[1], orders[2]
    paid_resp = service_post("%s/api/pay/mock?orderId=%d" % (ORDER, paid_order[0]))
    check("支付成功", paid_resp.get("code") == 200, paid_resp)

    cancel_resp = service_post("%s/api/orders/%d/cancel" % (ORDER, cancelled_order[0]),
                               user_id=cancelled_order[2])
    check("取消成功", cancel_resp.get("code") == 200, cancel_resp)

    print("  等待取消的库存回滚（Redis + product_stock）...")
    rolled = wait_until(
        lambda: redis_get("seckill:stock:%d" % PRODUCT_ID) == str(STOCK - 2)
        and mysql("SELECT stock FROM flashmall.product_stock WHERE product_id=%d;"
                  % PRODUCT_ID).strip() == str(STOCK - 2))
    check("取消后 Redis 与仓库各回滚 1 件（=%d）" % (STOCK - 2), rolled,
          "redis=%s db=%s" % (redis_get("seckill:stock:%d" % PRODUCT_ID),
                              mysql("SELECT stock FROM flashmall.product_stock WHERE product_id=%d;"
                                    % PRODUCT_ID).strip()))

    print("\n### 4. 统计快照")
    stats_resp = admin("GET", "/api/admin/seckill/events/%d/stats" % event_id)
    stats = stats_resp.get("data") or {}
    s = stats.get("stats") or {}
    stock = stats.get("stock") or {}
    ev = stats.get("event") or {}
    check("统计接口返回成功", stats_resp.get("code") == 200, stats_resp)
    check("event.status=running", ev.get("status") == "running", ev)
    check("event.seckillPrice=9.90", float(ev.get("seckillPrice") or 0) == PRICE, ev)
    check("orderCount=3", s.get("orderCount") == 3, s)
    check("paidCount=1", s.get("paidCount") == 1, s)
    check("gmv=9.90（paidCount × 秒杀价，不是原价）",
          float(s.get("gmv") or 0) == PRICE, s)
    check("payRate=0.3333", float(s.get("payRate") or 0) == 0.3333, s)
    check("stockTotal=10", s.get("stockTotal") == STOCK, s)
    check("stockConsumed=2（净消耗，取消已回滚）", s.get("stockConsumed") == 2, s)
    check("consumeRate=0.2", float(s.get("consumeRate") or 0) == 0.2, s)
    check("redisStock=8", stock.get("redisStock") == STOCK - 2, stock)
    check("dbStock=8（product_stock 仓库剩余）", stock.get("dbStock") == STOCK - 2, stock)
    check("ordersOutstanding=1", stock.get("ordersOutstanding") == 1, stock)

    print("\n### 5. 不存在的活动")
    missing = admin("GET", "/api/admin/seckill/events/99999999/stats")
    check("活动不存在返回 code=500",
          missing.get("code") == 500 and "活动不存在" in missing.get("message", ""), missing)

    # 清理：把本场活动下线，避免留下未结束活动
    admin("PUT", "/api/admin/seckill/events/%d" % event_id,
          {"endTime": (datetime.now() - timedelta(seconds=1)).strftime(FMT)})

    print("\n=== 结果：%d 通过 / %d 失败 ===" % (len(PASS), len(FAIL)))
    print("活动 id：%s，订单：支付=%s 取消=%s 待支付=%s"
          % (event_id, paid_order[0], cancelled_order[0], pending_order[0]))
    if FAIL:
        print("失败项：", FAIL)
        raise SystemExit(1)


def fetch_orders(since):
    rows = mysql(
        "SELECT id,order_no,user_id,status FROM flashmall.`order` "
        "WHERE product_id=%d AND create_time >= '%s' ORDER BY id;" % (PRODUCT_ID, since))
    orders = []
    for line in rows.splitlines():
        parts = line.split("\t")
        if len(parts) == 4:
            orders.append((int(parts[0]), parts[1], int(parts[2]), int(parts[3])))
    return orders


if __name__ == "__main__":
    main()
