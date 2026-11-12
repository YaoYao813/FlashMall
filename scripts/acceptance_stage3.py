# -*- coding: utf-8 -*-
"""
阶段 3 验收：库存回滚幂等 + 订单状态 CAS。

覆盖五类历史缺陷：
  1. 同一条回滚消息投递两次 → 库存被退两次（仓库虚高，下一场活动预热校验照样通过 → 真超卖）
  2. 「先查再改」订单状态 → 支付线程与超时/取消线程互相覆盖
  3. 取消接口连点两次 → 第二条消息再退一次库存
  4. 订单还在 MQ 里没落库时点支付 → 报「订单不存在」，用户以为抢购失败
  5. 扣库存成功但插单失败 → 仓库永久少卖（P1-1 反向分支）

前置：docker compose up -d，RabbitMQ 15672 管理口可用。
运行：
    PYTHONIOENCODING=utf-8 python scripts/acceptance_stage3.py

注意：
  - 会重置商品 5 的 product_stock 为 50，并清空该商品在 stock_rollback_log 里的台账，属于测试夹具。
  - 场景 1 用的 orderId=888001 是凭空造的（回滚消费者不校验订单表），
    跑完会把库存/Redis/台账都恢复，避免污染后续对账。
"""
import base64
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
ORDER = "http://localhost:8083"
RABBIT_API = "http://localhost:15672/api"
RABBIT_AUTH = base64.b64encode(b"admin:123456").decode()
EXCHANGE = "seckill.direct.exchange"
ROLLBACK_ROUTING_KEY = "seckill.stock.rollback"

TZ8 = timezone(timedelta(hours=8))
FMT = "%Y-%m-%dT%H:%M:%S"
PRODUCT_ID = 5
WAREHOUSE_STOCK = 50
EVENT_STOCK = 10
PRICE = 9.90
FAKE_ORDER_ID = 888001

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
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        return e.code, (json.loads(raw) if raw else {})


def admin(method, path, body=None, idem_key=None):
    headers = {"Authorization": "Bearer " + TOKEN}
    if idem_key:
        headers["Idempotency-Key"] = idem_key
    status, payload = http(method, GATEWAY + path, body, headers)
    if status != 200:
        raise AssertionError("HTTP %s: %s" % (status, payload))
    return payload


def docker(*args):
    result = subprocess.run(["docker"] + list(args), capture_output=True, text=True,
                            encoding="utf-8")
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip())
    return result.stdout.strip()


def mysql(sql):
    return docker("exec", "flashmall-mysql", "mysql", "-uroot", "-proot123",
                  "-N", "-B", "-e", sql)


def redis(*args):
    return docker("exec", "flashmall-redis", "redis-cli", *args)


def publish_rollback(body):
    """直接往交换机投一条回滚消息，模拟 MQ 重投 / 人工重放。"""
    status, resp = http(
        "POST",
        "%s/exchanges/%%2f/%s/publish" % (RABBIT_API, EXCHANGE),
        {"properties": {"content_type": "application/json", "delivery_mode": 2},
         "routing_key": ROLLBACK_ROUTING_KEY,
         "payload": json.dumps(body), "payload_encoding": "string"},
        {"Authorization": "Basic " + RABBIT_AUTH})
    if status != 200 or not resp.get("routed"):
        raise AssertionError("发布回滚消息失败：%s %s" % (status, resp))


def publish_seckill(body):
    """往主队列投一条秒杀订单消息（用来构造「插单必失败」的毒消息）。"""
    status, resp = http(
        "POST",
        "%s/exchanges/%%2f/%s/publish" % (RABBIT_API, EXCHANGE),
        {"properties": {"content_type": "application/json", "delivery_mode": 2},
         "routing_key": "seckill.order.create",
         "payload": json.dumps(body), "payload_encoding": "string"},
        {"Authorization": "Basic " + RABBIT_AUTH})
    if status != 200 or not resp.get("routed"):
        raise AssertionError("发布秒杀消息失败：%s %s" % (status, resp))


def dlq_depth():
    status, body = http("GET", "%s/queues/%%2f/seckill.order.dlq" % RABBIT_API,
                        headers={"Authorization": "Basic " + RABBIT_AUTH})
    if status != 200:
        raise AssertionError("读取死信队列失败：%s %s" % (status, body))
    return int(body.get("messages", 0))


def purge_queue(name):
    http("DELETE", "%s/queues/%%2f/%s/contents" % (RABBIT_API, name), None,
         {"Authorization": "Basic " + RABBIT_AUTH})


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


def db_stock():
    return int(mysql("SELECT stock FROM flashmall.product_stock WHERE product_id=%d;"
                     % PRODUCT_ID))


def redis_stock():
    return int(redis("GET", "seckill:stock:%d" % PRODUCT_ID))


def ledger_rows(order_id):
    return int(mysql("SELECT COUNT(*) FROM flashmall.stock_rollback_log WHERE order_id=%d;"
                     % order_id))


def order_status(order_id):
    return int(mysql("SELECT status FROM flashmall.order WHERE id=%d;" % order_id))


def downline_unfinished(product_id):
    for status in ("pending", "running"):
        resp = admin("GET", "/api/admin/seckill/events?status=%s&productId=%d&size=20"
                     % (status, product_id))
        for record in (resp.get("data") or {}).get("records") or []:
            admin("PUT", "/api/admin/seckill/events/%d" % record["id"],
                  {"endTime": (datetime.now() - timedelta(seconds=2)).strftime(FMT)})


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


def seckill_and_wait(user_id, since, expect_count):
    status, resp = http("POST", "%s/api/seckill?productId=%d" % (SECKILL, PRODUCT_ID),
                        None, {"X-User-Id": str(user_id)})
    if resp.get("code") != 200:
        raise SystemExit("秒杀失败：%s" % resp)
    if not wait_until(lambda: len(fetch_orders(since)) >= expect_count):
        raise SystemExit("订单没有落库：%s" % fetch_orders(since))
    return fetch_orders(since)[-1]


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
    mysql("DELETE FROM flashmall.stock_rollback_log WHERE product_id=%d;" % PRODUCT_ID)
    order_since = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    print("\n### 1. 建活动并预热")
    start = datetime.now() + timedelta(seconds=6)
    end = start + timedelta(hours=1)
    created = admin("POST", "/api/admin/seckill/events", {
        "productId": PRODUCT_ID,
        "seckillPrice": PRICE,
        "stock": EVENT_STOCK,
        "startTime": start.strftime(FMT),
        "endTime": end.strftime(FMT),
    }, idem_key="stage3-accept-%d" % int(time.time()))
    event_id = (created.get("data") or {}).get("id")
    check("创建活动成功", created.get("code") == 200 and event_id is not None, created)
    reset = admin("POST", "/api/admin/seckill/events/%d/stock-sync" % event_id, {"mode": "reset"})
    check("预热成功", reset.get("code") == 200, reset)
    wait = max(0, (start - datetime.now()).total_seconds()) + 1.5
    print("  等待 %.1f 秒到活动开始..." % wait)
    time.sleep(wait)

    print("\n### 2. 同一条回滚消息投两次 → 只退一次")
    db0, rd0 = db_stock(), redis_stock()
    body = {"orderId": FAKE_ORDER_ID, "orderNo": "FAKE-888001", "productId": PRODUCT_ID,
            "userId": 9001, "reason": "CANCEL"}
    publish_rollback(body)
    wait_until(lambda: ledger_rows(FAKE_ORDER_ID) == 1)
    publish_rollback(body)
    time.sleep(2)
    check("台账只记 1 行", ledger_rows(FAKE_ORDER_ID) == 1, ledger_rows(FAKE_ORDER_ID))
    check("DB 库存只 +1", db_stock() == db0 + 1, "before=%d after=%d" % (db0, db_stock()))
    check("Redis 库存只 +1", redis_stock() == rd0 + 1, "before=%d after=%d" % (rd0, redis_stock()))

    print("\n### 3. 老格式消息（缺 orderId）→ 拒绝处理")
    publish_rollback({"productId": PRODUCT_ID, "userId": 9002})
    time.sleep(2)
    check("老格式消息不改库存", db_stock() == db0 + 1 and redis_stock() == rd0 + 1,
          "db=%d redis=%d" % (db_stock(), redis_stock()))

    print("  恢复场景 2/3 的夹具...")
    mysql("USE flashmall; UPDATE product_stock SET stock = stock - 1 WHERE product_id=%d;"
          % PRODUCT_ID)
    redis("DECRBY", "seckill:stock:%d" % PRODUCT_ID, "1")
    mysql("DELETE FROM flashmall.stock_rollback_log WHERE order_id=%d;" % FAKE_ORDER_ID)
    check("夹具已恢复（DB/Redis 回到活动初始值）",
          db_stock() == WAREHOUSE_STOCK and redis_stock() == EVENT_STOCK,
          "db=%d redis=%d" % (db_stock(), redis_stock()))

    print("\n### 4. 取消接口连点两次 → 只退一次库存")
    user_a = 5_000_000 + int(time.time()) % 100_000 * 10 + 1
    order_a = seckill_and_wait(user_a, order_since, 1)
    wait_until(lambda: db_stock() == WAREHOUSE_STOCK - 1)
    resp1 = http("POST", "%s/api/orders/%d/cancel" % (ORDER, order_a[0]), None,
                 {"X-User-Id": str(user_a)})[1]
    resp2 = http("POST", "%s/api/orders/%d/cancel" % (ORDER, order_a[0]), None,
                 {"X-User-Id": str(user_a)})[1]
    check("第一次取消 code=200", resp1.get("code") == 200, resp1)
    check("第二次取消 code=200（幂等，不是报错）", resp2.get("code") == 200, resp2)
    wait_until(lambda: db_stock() == WAREHOUSE_STOCK)
    time.sleep(2)
    check("库存只退回 1 件", db_stock() == WAREHOUSE_STOCK, db_stock())
    check("Redis 库存只退回 1 件", redis_stock() == EVENT_STOCK, redis_stock())
    check("台账该订单只有 1 行", ledger_rows(order_a[0]) == 1, ledger_rows(order_a[0]))
    check("订单状态=已取消(4)", order_status(order_a[0]) == 4, order_status(order_a[0]))

    print("\n### 5. 支付语义：重复支付 / 已取消后支付")
    user_b = user_a + 1
    order_b = seckill_and_wait(user_b, order_since, 2)
    wait_until(lambda: db_stock() == WAREHOUSE_STOCK - 1)
    pay1 = http("POST", "%s/api/pay/mock?orderId=%d" % (ORDER, order_b[0]))[1]
    pay2 = http("POST", "%s/api/pay/mock?orderId=%d" % (ORDER, order_b[0]))[1]
    check("第一次支付 code=200", pay1.get("code") == 200, pay1)
    check("重复支付 code=500 且提示已支付",
          pay2.get("code") == 500 and "已支付" in pay2.get("message", ""), pay2)
    check("已支付订单不能再取消",
          "不允许取消" in http("POST", "%s/api/orders/%d/cancel" % (ORDER, order_b[0]), None,
                              {"X-User-Id": str(user_b)})[1].get("message", ""), "")
    check("支付后库存不再回滚", db_stock() == WAREHOUSE_STOCK - 1, db_stock())

    pay_a = http("POST", "%s/api/pay/mock?orderId=%d" % (ORDER, order_a[0]))[1]
    check("已取消订单支付 → 提示已取消",
          pay_a.get("code") == 500 and "已取消" in pay_a.get("message", ""), pay_a)

    print("\n### 6. 已超时订单支付 → 提示已超时")
    user_c = user_a + 2
    order_c = seckill_and_wait(user_c, order_since, 3)
    wait_until(lambda: db_stock() == WAREHOUSE_STOCK - 2)
    # 模拟超时：改状态 + 发回滚消息（真实链路里是延迟消息做的）
    mysql("USE flashmall; UPDATE `order` SET status=5 WHERE id=%d;" % order_c[0])
    publish_rollback({"orderId": order_c[0], "orderNo": order_c[1], "productId": PRODUCT_ID,
                      "userId": user_c, "reason": "TIMEOUT"})
    wait_until(lambda: db_stock() == WAREHOUSE_STOCK - 1)
    pay_c = http("POST", "%s/api/pay/mock?orderId=%d" % (ORDER, order_c[0]))[1]
    check("已超时订单支付 → 提示已超时",
          pay_c.get("code") == 500 and "已超时" in pay_c.get("message", ""), pay_c)

    print("\n### 7. 订单还在异步落库时支付 → 202 可重试")
    fresh_no = datetime.now().strftime("%Y%m%d%H%M%S") + "000" + "123456"
    resp_202 = http("POST", "%s/api/pay/mock?orderNo=%s" % (ORDER, fresh_no))[1]
    check("新生成的订单号 → code=202",
          resp_202.get("code") == 202 and "处理中" in resp_202.get("message", ""), resp_202)
    resp_404 = http("POST", "%s/api/pay/mock?orderNo=%s" % (ORDER, "ORD-NOT-EXIST-123"))[1]
    check("不认识的订单号 → code=500 订单不存在",
          resp_404.get("code") == 500 and "不存在" in resp_404.get("message", ""), resp_404)

    print("\n### 8. 取消与支付并发 → 只允许一个终态")
    rounds = 3
    for i in range(rounds):
        user_x = user_a + 10 + i
        order_x = seckill_and_wait(user_x, order_since, 4 + i)

        results = {}

        def do_pay():
            results["pay"] = http("POST", "%s/api/pay/mock?orderId=%d" % (ORDER, order_x[0]))[1]

        def do_cancel():
            results["cancel"] = http("POST", "%s/api/orders/%d/cancel" % (ORDER, order_x[0]),
                                     None, {"X-User-Id": str(user_x)})[1]

        threads = [threading.Thread(target=do_pay), threading.Thread(target=do_cancel)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        status_x = order_status(order_x[0])
        ok = status_x in (1, 4)
        check("第 %d 轮：终态唯一（status=%d）" % (i + 1, status_x), ok,
              "pay=%s cancel=%s" % (results.get("pay"), results.get("cancel")))
        time.sleep(1.5)

    print("\n### 9. 扣库存成功、插单失败 → 事务回滚，仓库不变（P1-1 反向分支）")
    purge_queue("seckill.order.dlq")
    db_before, rd_before = db_stock(), redis_stock()
    # 模拟 Lua 已经预减：消息进来前 Redis 少 1。消费者失败时会把它补回来。
    redis("DECRBY", "seckill:stock:%d" % PRODUCT_ID, "1")
    publish_seckill({"userId": 7001, "productId": PRODUCT_ID, "orderNo": "X" * 200})
    wait_until(lambda: dlq_depth() >= 1, timeout=25)
    time.sleep(1)
    check("仓库库存不变（扣减与插单同成同败）", db_stock() == db_before,
          "before=%d after=%d" % (db_before, db_stock()))
    check("Redis 已回补到预减前的值", redis_stock() == rd_before,
          "before=%d after=%d" % (rd_before, redis_stock()))
    check("毒消息进了死信队列", dlq_depth() >= 1, dlq_depth())
    purge_queue("seckill.order.dlq")

    print("\n### 10. 全部订单进入终态后对账应 OK")
    wait_until(lambda: db_stock() == redis_stock())
    resp = admin("GET", "/api/admin/seckill/reconcile?eventId=%d" % event_id)
    vo = resp.get("data") or {}
    check("对账 overall=OK", vo.get("verdict") == "OK", vo)

    admin("PUT", "/api/admin/seckill/events/%d" % event_id,
          {"endTime": (datetime.now() - timedelta(seconds=1)).strftime(FMT)})

    print("\n== 结果：PASS %d / FAIL %d ==" % (len(PASS), len(FAIL)))
    if FAIL:
        print("失败项：" + ", ".join(FAIL))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
