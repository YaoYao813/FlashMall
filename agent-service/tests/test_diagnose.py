"""diagnose_stock 改为双向对账（GET /reconcile）后的行为测试。

判定口径在 Java 侧（契约 §1.7），Agent 只做翻译：
DRIFT 才算确认漂移，UNKNOWN / STALE 不能当成「正常」。
"""

import pytest

from app.clients.errors import BizError
from mcp_servers import commerce_server
from mcp_servers.commerce_server import _diagnose_one, diagnose_stock


def reconcile(
    verdict="OK",
    fingerprint="NONE",
    quota=None,
    warehouse=None,
    backlog=0,
):
    quota = quota if quota is not None else {
        "total": 10,
        "redisStock": 8,
        "orderCount": 2,
        "cancelledCount": 0,
        "expectedRedis": 8,
        "gap": 0,
        "verdict": verdict,
    }
    warehouse = warehouse if warehouse is not None else {
        "baseline": 50,
        "dbStock": 48,
        "expectedDb": 48,
        "gap": 0,
        "verdict": verdict,
    }
    return {
        "eventId": 9,
        "productId": 7,
        "status": "running",
        "verdict": verdict,
        "fingerprint": fingerprint,
        "backlog": backlog,
        "quota": quota,
        "warehouse": warehouse,
    }


class FakeClient:
    def __init__(self, *, single=None, all_items=None, error=None):
        self.single = single
        self.all_items = all_items or []
        self.error = error
        self.calls = []

    async def reconcile(self, event_id):
        self.calls.append(("reconcile", event_id))
        if self.error:
            raise self.error
        return self.single

    async def reconcile_all(self):
        self.calls.append(("reconcile_all", None))
        if self.error:
            raise self.error
        return self.all_items


@pytest.fixture
def use_client(monkeypatch):
    def install(client):
        monkeypatch.setattr(commerce_server, "get_client", lambda: client)
        return client

    return install


def test_ok_and_pending_are_not_suspicious():
    assert _diagnose_one(reconcile("OK")) is None
    assert _diagnose_one(reconcile("PENDING")) is None


def test_drift_reports_fingerprint_and_both_gaps():
    finding = _diagnose_one(
        reconcile(
            "DRIFT",
            "DUPLICATE_ROLLBACK",
            quota={"total": 10, "redisStock": 10, "orderCount": 2, "cancelledCount": 1,
                   "expectedRedis": 9, "gap": -1, "verdict": "DRIFT"},
            warehouse={"baseline": 50, "dbStock": 50, "expectedDb": 49,
                       "gap": -1, "verdict": "DRIFT"},
        )
    )

    assert finding["verdict"] == "DRIFT"
    assert finding["fingerprint"] == "DUPLICATE_ROLLBACK"
    assert any("回滚消息执行了两次" in reason for reason in finding["reasons"])
    assert any("配额侧" in reason and "gap=-1" in reason for reason in finding["reasons"])
    assert any("仓库侧" in reason and "gap=-1" in reason for reason in finding["reasons"])


def test_unknown_side_explains_what_is_missing():
    finding = _diagnose_one(
        reconcile(
            "UNKNOWN",
            quota={"total": 10, "orderCount": 0, "cancelledCount": 0, "verdict": "UNKNOWN"},
            warehouse={"dbStock": 50, "verdict": "UNKNOWN"},
        )
    )

    assert finding["verdict"] == "UNKNOWN"
    assert any("Redis 库存不可读" in reason for reason in finding["reasons"])
    assert any("预热基线" in reason for reason in finding["reasons"])


def test_stale_is_reported_not_treated_as_healthy():
    finding = _diagnose_one(reconcile("STALE"))

    assert finding is not None
    assert any("更新的场次" in reason for reason in finding["reasons"])


async def test_single_event_uses_reconcile_endpoint(use_client):
    client = use_client(FakeClient(single=reconcile("OK")))

    result = await diagnose_stock(eventId=9)

    assert client.calls == [("reconcile", 9)]
    assert result["ok"] is True
    assert result["data"]["checked"] == 1
    assert result["data"]["suspicious"] == []
    assert "都能对上" in result["data"]["message"]


async def test_all_events_uses_reconcile_all(use_client):
    drift = reconcile("DRIFT", "ROLLBACK_MISSED")
    client = use_client(
        FakeClient(all_items=[reconcile("OK"), reconcile("PENDING"), drift])
    )

    result = await diagnose_stock()

    assert client.calls == [("reconcile_all", None)]
    data = result["data"]
    assert data["checked"] == 3
    assert [item["fingerprint"] for item in data["suspicious"]] == ["ROLLBACK_MISSED"]
    assert "1 个确认库存漂移" in data["message"]


async def test_no_reconcilable_events(use_client):
    use_client(FakeClient(all_items=[]))

    result = await diagnose_stock()

    assert result["data"] == {
        "checked": 0,
        "suspicious": [],
        "message": "当前没有可对账的活动",
    }


async def test_business_error_is_wrapped(use_client):
    use_client(FakeClient(error=BizError("活动不存在")))

    result = await diagnose_stock(eventId=99)

    assert result["ok"] is False
    assert result["error"]["message"] == "活动不存在"
