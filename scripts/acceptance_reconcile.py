# -*- coding: utf-8 -*-
"""
阶段 2 验收：库存对账 GET /api/admin/seckill/reconcile。

验证两条不变量：
    配额侧：event.stock - redisStock        = 订单数 - 取消数 + 在途
    仓库侧：warehouse_baseline - dbStock    = 订单数 - 取消数 + 在途

四个场景：
  1. 正常买卖 + 取消一单 → OK
  2. 手工 DECRBY Redis 2 → 配额侧 DRIFT，gap=2
  3. 手工扣 product_stock 2 → 仓库侧 DRIFT，gap=2
  4. 恢复现场 → 重新 OK

前置：docker compose up -d，网关 8888、seckill-service 8080、order-service 8083 可用。
运行：
    PYTHONIOENCODING=utf-8 python scripts/acceptance_reconcile.py

注意：会重置商品 4 的 product_stock 为 50，属于测试夹具。
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
PRODUCT_ID = 4
WAREHOUSE_STOCK = 50
EVENT_STOCK = 10
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


def docker(*args):
    result = subprocess.run(["docker"] + list(args), capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip())
    return result.stdout.strip()


def mysql(sql):
    return docker("exec", "flashmall-mysql", "mysql", "-uroot", "-proot123",
                  "-N", "-B", "-e", sql)


def redis(*args):
    return docker("exec", "flashmall-redis", "redis-cli", *args)


def check(name, cond, detail=""):
    if cond:
        PASS.append(name)
        print("  [PASS] %s" % name)
    else:
        FAIL.append(name)
        print("  [FAIL] %s  %s" % (name, detail))


def wait_until(predicate, timeout=25, interval=0.5):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if predicate():
            return True
        time.sleep(interval)
    return False


def downline_unfinished(product_id):
    for status in ("pending", "running"):
        resp = admin("GET", "/api/admin/seckill/events?status=%s&productId=%d&size=20"
                     % (status, product_id))
        for record in (resp.get("data") or {}).get("records") or []:
            admin("PUT", "/api/admin/seckill/events/%d" % record["id"],
                  {"endTime": (datetime.now() - timedelta(seconds=2)).strftime(FMT)})


def reconcile(event_id):
    resp = admin("GET", "/api/admin/seckill/reconcile?eventId=%d" % event_id)
    if resp.get("code") != 200:
        raise AssertionError("对账接口失败：%s" % resp)
    return resp["data"]


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


def main():
    global TOKEN

    print("### 0. 登录与夹具清理")
    status, login = http("POST", GATEWAY + "/api/auth/login",
                         {"username": "testbuyer", "password": "123456"})
    if status != 200 or login.get("code") != 200:
        raise SystemExit("登录失败，确认 docker compose 已启动：%s" % login)
    TOKEN = login["data"]

    downline_unfinished(PRODUCT_ID)
    redis("DEL", "seckill:event:%d" % PRODUCT_ID)
    redis("DEL", "seckill:stock:%d" % PRODUCT_ID)
    mysql("USE flashmall; UPDATE product_stock SET stock=%d, version=0 WHERE product_id=%d;"
          % (WAREHOUSE_STOCK, PRODUCT_ID))
    order_since = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print("  已清理商品 %d 的未结束活动，重置 product_stock=%d" % (PRODUCT_ID, WAREHOUSE_STOCK))

    print("\n### 1. 建活动并预热（同时写入仓库基线 D0）")
    start = datetime.now() + timedelta(seconds=6)
    end = start + timedelta(hours=1)
    created = admin("POST", "/api/admin/seckill/events", {
        "productId": PRODUCT_ID,
        "seckillPrice": PRICE,
        "stock": EVENT_STOCK,
        "startTime": start.strftime(FMT),
        "endTime": end.strftime(FMT),
    }, idem_key="reconcile-accept-%d" % int(time.time()))
    event_id = (created.get("data") or {}).get("id")
    check("创建活动成功", created.get("code") == 200 and event_id is not None, created)

    reset = admin("POST", "/api/admin/seckill/events/%d/stock-sync" % event_id, {"mode": "reset"})
    check("预热成功", reset.get("code") == 200, reset)

    baseline = mysql("SELECT warehouse_baseline FROM flashmall.seckill_event WHERE id=%d;"
                     % event_id).strip()
    check("仓库基线已记录 D0=%d" % WAREHOUSE_STOCK, baseline == str(WAREHOUSE_STOCK),
          "baseline=%s" % baseline)

    wait = max(0, (start - datetime.now()).total_seconds()) + 1.5
    print("  等待 %.1f 秒到活动开始..." % wait)
    time.sleep(wait)

    print("\n### 2. 三个用户各抢一单，其中一单取消")
    base_user = 4_000_000 + (int(time.time()) % 1_000_000) * 100
    users = [base_user + i for i in (1, 2, 3)]
    for user_id in users:
        resp = http("POST", "%s/api/seckill?productId=%d" % (SECKILL, PRODUCT_ID),
                    None, {"X-User-Id": str(user_id)})[1]
        if resp.get("code") != 200:
            raise SystemExit("秒杀失败：%s" % resp)

    orders = []
    if not wait_until(lambda: len(fetch_orders(order_since)) == 3):
        raise SystemExit("订单没有全部落库：%s" % fetch_orders(order_since))
    orders = fetch_orders(order_since)
    check("订单恰好 3 条", len(orders) == 3, orders)

    cancelled = orders[1]
    http("POST", "%s/api/orders/%d/cancel" % (ORDER, cancelled[0]), None,
         {"X-User-Id": str(cancelled[2])})
    rolled = wait_until(
        lambda: redis("GET", "seckill:stock:%d" % PRODUCT_ID) == str(EVENT_STOCK - 2)
        and mysql("SELECT stock FROM flashmall.product_stock WHERE product_id=%d;"
                  % PRODUCT_ID).strip() == str(WAREHOUSE_STOCK - 2))
    check("取消后 Redis 与仓库各回滚 1 件", rolled,
          "redis=%s db=%s" % (redis("GET", "seckill:stock:%d" % PRODUCT_ID),
                              mysql("SELECT stock FROM flashmall.product_stock "
                                    "WHERE product_id=%d;" % PRODUCT_ID).strip()))

    print("\n### 3. 正常状态：两侧都应 OK")
    wait_until(lambda: reconcile(event_id)["verdict"] != "PENDING")
    vo = reconcile(event_id)
    check("overall=OK", vo["verdict"] == "OK", vo)
    check("配额侧 OK 且 gap=0", (vo["quota"]["verdict"], vo["quota"]["gap"]) == ("OK", 0),
          vo["quota"])
    check("仓库侧 OK 且 gap=0", (vo["warehouse"]["verdict"], vo["warehouse"]["gap"]) == ("OK", 0),
          vo["warehouse"])
    check("orderCount=3 / cancelledCount=1",
          (vo["quota"]["orderCount"], vo["quota"]["cancelledCount"]) == (3, 1), vo["quota"])
    check("expectedRedis=8", vo["quota"]["expectedRedis"] == EVENT_STOCK - 2, vo["quota"])
    check("expectedDb=48", vo["warehouse"]["expectedDb"] == WAREHOUSE_STOCK - 2, vo["warehouse"])

    all_resp = admin("GET", "/api/admin/seckill/reconcile/all")
    ids = [item.get("eventId") for item in (all_resp.get("data") or [])]
    check("批量对账包含本场活动", event_id in ids, ids)

    print("\n### 4. 人为让 Redis 少 2 件 → 配额侧 DRIFT")
    redis("DECRBY", "seckill:stock:%d" % PRODUCT_ID, "2")
    vo = reconcile(event_id)
    check("overall=DRIFT", vo["verdict"] == "DRIFT", vo)
    check("配额侧 DRIFT 且 gap=2", (vo["quota"]["verdict"], vo["quota"]["gap"]) == ("DRIFT", 2),
          vo["quota"])
    check("仓库侧仍 OK", vo["warehouse"]["verdict"] == "OK", vo["warehouse"])
    redis("INCRBY", "seckill:stock:%d" % PRODUCT_ID, "2")

    print("\n### 5. 人为让仓库少 2 件 → 仓库侧 DRIFT")
    mysql("USE flashmall; UPDATE product_stock SET stock = stock - 2 WHERE product_id=%d;"
          % PRODUCT_ID)
    vo = reconcile(event_id)
    check("仓库侧 DRIFT 且 gap=2",
          (vo["warehouse"]["verdict"], vo["warehouse"]["gap"]) == ("DRIFT", 2), vo["warehouse"])
    check("配额侧仍 OK", vo["quota"]["verdict"] == "OK", vo["quota"])
    mysql("USE flashmall; UPDATE product_stock SET stock = stock + 2 WHERE product_id=%d;"
          % PRODUCT_ID)

    print("\n### 6. 恢复现场后重新 OK")
    vo = reconcile(event_id)
    check("恢复后 overall=OK", vo["verdict"] == "OK", vo)

    print("\n== 结果：PASS %d / FAIL %d ==" % (len(PASS), len(FAIL)))
    if FAIL:
        print("失败项：" + ", ".join(FAIL))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
