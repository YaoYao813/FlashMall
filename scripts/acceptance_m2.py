# -*- coding: utf-8 -*-
"""
M2 端到端验收：接口 4 stock-sync + 秒杀前置校验 + 并发零超卖。

前置：docker compose up -d，网关 8888、seckill-service 8080 可用。
运行：
    PYTHONIOENCODING=utf-8 python scripts/acceptance_m2.py

说明：
- 管理接口走网关 8888（真实 JWT）；秒杀压测直连 8080 并伪造 X-User-Id，
  这是为了用 100 个不同用户压测，不经过网关登录，不影响被测逻辑。
- 会重置商品 1/2 的 product_stock 为 50，属于测试夹具，运行前请知悉。
"""
import json
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

GATEWAY = "http://localhost:8888"
SECKILL = "http://localhost:8080"
TZ8 = timezone(timedelta(hours=8))
FMT = "%Y-%m-%dT%H:%M:%S"

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


def seckill(user_id, product_id):
    url = "%s/api/seckill?productId=%d" % (SECKILL, product_id)
    req = urllib.request.Request(
        url, data=b"", method="POST", headers={"X-User-Id": str(user_id)})
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode("utf-8", "replace"))


def docker(*args):
    result = subprocess.run(["docker"] + list(args), capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip())
    return result.stdout.strip()


def redis_get(key):
    return docker("exec", "flashmall-redis", "redis-cli", "GET", key)


def redis_del(key):
    docker("exec", "flashmall-redis", "redis-cli", "DEL", key)


def redis_ttl(key):
    return docker("exec", "flashmall-redis", "redis-cli", "TTL", key)


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


def epoch_ms(dt):
    # 请求里的时间字符串只到秒，比较时同样截断微秒
    return int(dt.replace(microsecond=0, tzinfo=TZ8).timestamp() * 1000)


def downline_unfinished(product_id):
    for status in ("pending", "running"):
        resp = admin("GET", "/api/admin/seckill/events?status=%s&productId=%d&size=20"
                     % (status, product_id))
        for record in (resp.get("data") or {}).get("records") or []:
            admin("PUT", "/api/admin/seckill/events/%d" % record["id"],
                  {"endTime": (datetime.now() - timedelta(seconds=2)).strftime(FMT)})


def create_event(product_id, stock, price, start, end, key):
    return admin("POST", "/api/admin/seckill/events", {
        "productId": product_id,
        "seckillPrice": price,
        "stock": stock,
        "startTime": start.strftime(FMT),
        "endTime": end.strftime(FMT),
    }, idem_key=key)


def main():
    global TOKEN

    print("### 0. 登录与夹具清理")
    status, login = http("POST", GATEWAY + "/api/auth/login",
                         {"username": "testbuyer", "password": "123456"})
    if status != 200 or login.get("code") != 200:
        raise SystemExit("登录失败，确认 docker compose 已启动：%s" % login)
    TOKEN = login["data"]

    for product_id in (1, 2):
        downline_unfinished(product_id)
        redis_del("seckill:event:%d" % product_id)
        redis_del("seckill:stock:%d" % product_id)
    mysql("USE flashmall; UPDATE product_stock SET stock=50, version=0 WHERE product_id IN (1,2);")
    print("  已清理商品 1/2 的未结束活动，重置 product_stock=50")

    now = datetime.now()
    start = now + timedelta(seconds=10)
    end = start + timedelta(hours=2)
    key = "m2-accept-%d" % int(time.time())

    # ==================== A. 功能链路（商品 1） ====================
    print("\n### 1. 建活动 + reset 预热")
    created = create_event(1, 50, 39.9, start, end, key)
    event = created.get("data") or {}
    event_id = event.get("id")
    check("创建活动成功且为 pending", created.get("code") == 200 and event.get("status") == "pending",
          created)

    stock_key = "seckill:stock:1"
    window_key = "seckill:event:1"

    print("\n### 1.1 仓库库存不足时拒绝预热")
    mysql("USE flashmall; UPDATE product_stock SET stock=10 WHERE product_id=1;")
    insufficient = admin("POST", "/api/admin/seckill/events/%d/stock-sync" % event_id, {"mode": "reset"})
    check("仓库库存不足时 reset 被拒",
          insufficient.get("code") == 500 and "仓库库存不足" in insufficient.get("message", ""),
          insufficient)
    check("被拒时未写入活动窗口", redis_get(window_key) == "", redis_get(window_key))
    mysql("USE flashmall; UPDATE product_stock SET stock=50 WHERE product_id=1;")

    reset = admin("POST", "/api/admin/seckill/events/%d/stock-sync" % event_id, {"mode": "reset"})
    reset_data = reset.get("data") or {}
    check("reset 成功", reset.get("code") == 200, reset)
    check("reset 响应 dbStock=redisStock=50",
          reset_data.get("dbStock") == 50 and reset_data.get("redisStock") == 50, reset)

    check("Redis 库存已写入 50", redis_get(stock_key) == "50", redis_get(stock_key))
    window_raw = redis_get(window_key)
    try:
        window = json.loads(window_raw)
    except ValueError:
        window = {}
    check("Redis 窗口写入 eventId", window.get("eventId") == event_id, window_raw)
    check("窗口 startTs 为 epoch 毫秒且时间正确",
          window.get("startTs") == epoch_ms(start), "%s vs %s" % (window.get("startTs"), epoch_ms(start)))
    check("窗口 endTs 为 epoch 毫秒且时间正确",
          window.get("endTs") == epoch_ms(end), window.get("endTs"))
    check("窗口 key 无 TTL", redis_ttl(window_key) == "-1", redis_ttl(window_key))

    print("\n### 2. 未开始 / 重复 / 售罄前置校验")
    base_user = 2_000_000 + (int(time.time()) % 1_000_000) * 100
    early = seckill(base_user + 1, 1)
    check("活动未开始被拒", early.get("code") == 500 and early.get("message") == "活动未开始",
          early)

    wait = max(0, (start - datetime.now()).total_seconds()) + 1.5
    print("  等待 %.1f 秒到活动开始..." % wait)
    time.sleep(wait)

    reset_running = admin("POST", "/api/admin/seckill/events/%d/stock-sync" % event_id, {"mode": "reset"})
    check("进行中的活动不能再 reset",
          reset_running.get("code") == 500 and "只有未开始" in reset_running.get("message", ""),
          reset_running)

    first = seckill(base_user + 1, 1)
    check("开始后秒杀成功", first.get("code") == 200 and first.get("data"), first)
    check("Redis 库存减为 49", redis_get(stock_key) == "49", redis_get(stock_key))

    duplicate = seckill(base_user + 1, 1)
    check("同一用户重复秒杀被拒",
          duplicate.get("code") == 500 and "已抢购" in duplicate.get("message", ""), duplicate)

    print("\n### 3. increment 调整")
    inc = admin("POST", "/api/admin/seckill/events/%d/stock-sync" % event_id,
                {"mode": "increment", "delta": 10})
    inc_data = inc.get("data") or {}
    check("increment +10 成功", inc.get("code") == 200, inc)
    check("dbStock=60（活动总配额）", inc_data.get("dbStock") == 60, inc)
    check("redisStock=59（当前剩余）", inc_data.get("redisStock") == 59, inc)
    check("Redis 库存同步为 59", redis_get(stock_key) == "59", redis_get(stock_key))

    rejected = admin("POST", "/api/admin/seckill/events/%d/stock-sync" % event_id,
                     {"mode": "increment", "delta": -999})
    check("increment -999 被拒",
          rejected.get("code") == 500 and "不能为负数" in rejected.get("message", ""), rejected)
    check("被拒后 Redis 库存不变", redis_get(stock_key) == "59", redis_get(stock_key))

    zero = admin("POST", "/api/admin/seckill/events/%d/stock-sync" % event_id,
                 {"mode": "increment", "delta": 0})
    check("delta=0 被拒", zero.get("code") == 500 and "不能为 0" in zero.get("message", ""), zero)

    print("\n### 4. 下线 / 未预热")
    down = admin("PUT", "/api/admin/seckill/events/%d" % event_id,
                 {"endTime": (datetime.now() - timedelta(seconds=1)).strftime(FMT)})
    check("下线成功且状态 ended", down.get("code") == 200 and (down.get("data") or {}).get("status") == "ended",
          down)
    window_after = json.loads(redis_get(window_key) or "{}")
    check("下线后窗口 endTs 已刷新到过去",
          window_after.get("endTs", 0) < int(time.time() * 1000), window_after)

    ended = seckill(base_user + 2, 1)
    check("活动已结束被拒", ended.get("code") == 500 and ended.get("message") == "活动已结束", ended)

    redis_del(window_key)
    unheated = seckill(base_user + 3, 1)
    check("删除窗口后按未预热处理",
          unheated.get("code") == 500 and unheated.get("message") == "活动不存在或未预热", unheated)

    # ==================== B. 并发零超卖（商品 2） ====================
    print("\n### 5. 并发回归：库存 50、100 人同时抢（商品 2）")
    mysql("USE flashmall; UPDATE product_stock SET stock=50, version=0 WHERE product_id=2;")
    redis_del("seckill:stock:2")
    redis_del("seckill:event:2")
    downline_unfinished(2)
    order_since = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    c_start = datetime.now() + timedelta(seconds=8)
    c_end = c_start + timedelta(hours=1)
    c_created = create_event(2, 50, 39.9, c_start, c_end, key + "-concurrency")
    c_event = c_created.get("data") or {}
    c_id = c_event.get("id")
    check("并发活动创建成功", c_created.get("code") == 200, c_created)
    c_reset = admin("POST", "/api/admin/seckill/events/%d/stock-sync" % c_id, {"mode": "reset"})
    check("并发活动预热成功", c_reset.get("code") == 200, c_reset)

    c_wait = max(0, (c_start - datetime.now()).total_seconds()) + 1.5
    print("  等待 %.1f 秒到活动开始..." % c_wait)
    time.sleep(c_wait)

    results = []
    results_lock = threading.Lock()

    def worker(user_id):
        resp = seckill(user_id, 2)
        with results_lock:
            results.append(resp)

    threads = []
    for i in range(100):
        t = threading.Thread(target=worker, args=(base_user + 1000 + i,))
        threads.append(t)
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    success = [r for r in results if r.get("code") == 200]
    out_of_stock = [r for r in results if r.get("code") == 500 and "库存不足" in r.get("message", "")]
    other = [r for r in results if r not in success and r not in out_of_stock]
    check("成功恰好 50 单", len(success) == 50, "success=%d" % len(success))
    check("其余 50 人库存不足", len(out_of_stock) == 50,
          "out_of_stock=%d other=%s" % (len(out_of_stock), other[:3]))
    check("无其他错误", not other, other[:3])
    check("Redis 库存归零", redis_get("seckill:stock:2") == "0", redis_get("seckill:stock:2"))

    print("  等待 MQ 消费者落库...")
    deadline = time.time() + 20
    db_stock = order_count = None
    while time.time() < deadline:
        db_stock = mysql("SELECT stock FROM flashmall.product_stock WHERE product_id=2;").strip()
        order_count = mysql(
            "SELECT COUNT(*) FROM flashmall.`order` WHERE product_id=2 AND create_time >= '%s';"
            % order_since).strip()
        if db_stock == "0" and order_count == "50":
            break
        time.sleep(0.5)
    check("DB 仓库库存归零", db_stock == "0", db_stock)
    check("订单恰好 50 条", order_count == "50", order_count)

    print("\n=== 结果：%d 通过 / %d 失败 ===" % (len(PASS), len(FAIL)))
    print("活动 id：功能=%s，并发=%s" % (event_id, c_id))
    if FAIL:
        print("失败项：", FAIL)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
