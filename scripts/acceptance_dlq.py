# -*- coding: utf-8 -*-
"""
阶段 1 验收：消费幂等 + 死信队列 + 死信重放。

不经过秒杀接口，直接往 MQ 发消息，专门验证消费者：
  1. 同一条消息投两次 → 只落一条订单、库存只扣一次（order_no 幂等过滤）
  2. 库存不足导致消费失败 → 消息进 seckill.order.dlq，不再静默丢失
  3. 恢复库存后调 /api/admin/seckill/dlq/replay → 消息被重新消费，订单补上

前置：docker compose up -d，网关 8888、RabbitMQ 15672 可用。
运行：
    PYTHONIOENCODING=utf-8 python scripts/acceptance_dlq.py

注意：会重置商品 2 的 product_stock 为 50，属于测试夹具。

已知干扰：如果 15 分钟内刚跑过 acceptance_m2（商品 2 的 50 单压测），
那批订单的延迟取消消息会在本脚本执行期间陆续触发并行回滚库存，
导致前两项「扣一次库存」偶发失败。等一分钟重跑即可，不是回归。
"""
import base64
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

GATEWAY = "http://localhost:8888"
RABBIT_API = "http://localhost:15672/api"
RABBIT_AUTH = base64.b64encode(b"admin:123456").decode()
EXCHANGE = "seckill.direct.exchange"
ROUTING_KEY = "seckill.order.create"
DLQ = "seckill.order.dlq"
PRODUCT_ID = 2

PASS = []
FAIL = []


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


def mysql(sql):
    out = subprocess.run(
        ["docker", "exec", "flashmall-mysql", "mysql", "-uroot", "-proot123",
         "-N", "-B", "-e", sql],
        capture_output=True, text=True, encoding="utf-8")
    if out.returncode != 0:
        raise RuntimeError(out.stderr)
    return out.stdout.strip()


def publish(order_no, user_id=9001):
    payload = json.dumps({"userId": user_id, "productId": PRODUCT_ID, "orderNo": order_no})
    status, body = http(
        "POST",
        "%s/exchanges/%%2f/%s/publish" % (RABBIT_API, EXCHANGE),
        {"properties": {"content_type": "application/json", "delivery_mode": 2},
         "routing_key": ROUTING_KEY, "payload": payload, "payload_encoding": "string"},
        {"Authorization": "Basic " + RABBIT_AUTH})
    if status != 200 or not body.get("routed"):
        raise AssertionError("发布消息失败：%s %s" % (status, body))


def dlq_depth():
    status, body = http("GET", "%s/queues/%%2f/%s" % (RABBIT_API, DLQ),
                        headers={"Authorization": "Basic " + RABBIT_AUTH})
    if status != 200:
        raise AssertionError("读取死信队列失败：%s %s" % (status, body))
    return int(body.get("messages", 0))


def order_count(order_no):
    return int(mysql(
        "SELECT COUNT(*) FROM flashmall.order WHERE order_no='%s';" % order_no))


def stock_of_product():
    return int(mysql(
        "SELECT stock FROM flashmall.product_stock WHERE product_id=%d;" % PRODUCT_ID))


def check(name, cond, detail=""):
    if cond:
        PASS.append(name)
        print("  [PASS] %s %s" % (name, detail))
    else:
        FAIL.append(name)
        print("  [FAIL] %s %s" % (name, detail))


def wait_until(fn, expected, timeout=15, interval=0.5):
    deadline = time.time() + timeout
    last = fn()
    while time.time() < deadline:
        if last == expected:
            return last
        time.sleep(interval)
        last = fn()
    return last


def login():
    status, body = http("POST", GATEWAY + "/api/auth/login",
                        {"username": "testbuyer", "password": "123456"})
    if status != 200 or body.get("code") != 200:
        raise SystemExit("登录失败，确认 docker compose 已启动：%s" % body)
    return body["data"]


def main():
    print("== 阶段 1 验收：消费幂等 / 死信 / 重放 ==")
    token = login()

    # 夹具：库存 50，清空死信
    mysql("USE flashmall; UPDATE product_stock SET stock=50, version=0 "
          "WHERE product_id=%d;" % PRODUCT_ID)
    baseline_stock = stock_of_product()
    print("  初始仓库库存 = %d" % baseline_stock)

    # ---------- 1. 同一条消息投两次 ----------
    order_no = "DLQTEST-%d" % int(time.time() * 1000)
    print("\n[1] 幂等：orderNo=%s 投两次" % order_no)
    publish(order_no)
    wait_until(lambda: order_count(order_no), 1)
    time.sleep(2)
    stock_after_first = stock_of_product()
    check("首次投递落库", order_count(order_no) == 1, "orderCount=%d" % order_count(order_no))
    check("首次投递扣一次库存", stock_after_first == baseline_stock - 1,
          "stock=%d" % stock_after_first)

    publish(order_no)
    time.sleep(3)
    stock_after_second = stock_of_product()
    check("重复投递不重复落库", order_count(order_no) == 1, "orderCount=%d" % order_count(order_no))
    check("重复投递不重复扣库存", stock_after_second == stock_after_first,
          "stock=%d" % stock_after_second)
    check("重复消息不进死信", dlq_depth() == 0, "dlq=%d" % dlq_depth())

    # ---------- 2. 制造消费失败 → 死信 ----------
    print("\n[2] 死信：库存置 0 后投递新消息")
    mysql("USE flashmall; UPDATE product_stock SET stock=0, version=0 "
          "WHERE product_id=%d;" % PRODUCT_ID)
    dlq_before = dlq_depth()
    fail_order_no = "DLQFAIL-%d" % int(time.time() * 1000)
    publish(fail_order_no)

    depth = wait_until(dlq_depth, dlq_before + 1)
    check("失败消息进入死信队列", depth == dlq_before + 1, "dlq=%d" % depth)
    check("失败消息没有落库", order_count(fail_order_no) == 0)

    # ---------- 3. 恢复库存 + 重放 ----------
    print("\n[3] 重放：恢复库存后调用 /dlq/replay")
    mysql("USE flashmall; UPDATE product_stock SET stock=50, version=0 "
          "WHERE product_id=%d;" % PRODUCT_ID)
    status, body = http("POST", GATEWAY + "/api/admin/seckill/dlq/replay?limit=10",
                        headers={"Authorization": "Bearer " + token})
    if status != 200 or body.get("code") != 200:
        check("重放接口可用", False, "%s %s" % (status, body))
        raise SystemExit(1)
    check("重放接口返回", body["data"].get("replayed", 0) >= 1, str(body["data"]))

    wait_until(lambda: order_count(fail_order_no), 1)
    check("重放后订单补上", order_count(fail_order_no) == 1)
    check("重放后死信清空", wait_until(dlq_depth, 0) == 0, "dlq=%d" % dlq_depth())

    print("\n== 结果：PASS %d / FAIL %d ==" % (len(PASS), len(FAIL)))
    if FAIL:
        print("失败项：" + ", ".join(FAIL))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
