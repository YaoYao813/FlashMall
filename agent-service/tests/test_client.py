"""HTTP 客户端行为测试：用 MockTransport，不依赖真实 Java 服务。"""

import httpx
import pytest

from app.clients.errors import AuthError, BizError, UpstreamError
from app.clients.flashmall import FlashMallClient


def ok(data):
    return httpx.Response(200, json={"code": 200, "message": "操作成功", "data": data})


def biz(message):
    return httpx.Response(200, json={"code": 500, "message": message, "data": None})


def unauthorized():
    return httpx.Response(401, json={"code": 401, "message": "Token 已过期，请重新登录"})


def make_client(handler) -> FlashMallClient:
    return FlashMallClient(
        base_url="http://gateway.test",
        username="admin",
        password="secret",
        transport=httpx.MockTransport(handler),
    )


@pytest.fixture
def calls():
    return []


async def test_logs_in_then_returns_data(calls):
    def handler(request: httpx.Request) -> httpx.Response:
        calls.append((request.method, request.url.path))
        if request.url.path == "/api/auth/login":
            return ok("token-1")
        assert request.headers["Authorization"] == "Bearer token-1"
        return ok({"records": [], "total": 0, "pages": 0})

    client = make_client(handler)
    data = await client.list_events(status="running")

    assert data["total"] == 0
    assert [path for _, path in calls] == ["/api/auth/login", "/api/admin/seckill/events"]


async def test_business_error_maps_to_biz_error(calls):
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/auth/login":
            return ok("token-1")
        return biz("活动不存在")

    client = make_client(handler)
    with pytest.raises(BizError) as exc:
        await client.get_stats(99)

    assert exc.value.message == "活动不存在"
    assert exc.value.code == "BIZ"


async def test_401_triggers_one_relogin_then_succeeds(calls):
    state = {"logins": 0, "requests": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/auth/login":
            state["logins"] += 1
            return ok(f"token-{state['logins']}")
        state["requests"] += 1
        if state["requests"] == 1:
            return unauthorized()
        assert request.headers["Authorization"] == "Bearer token-2"
        return ok({"records": [], "total": 0, "pages": 0})

    client = make_client(handler)
    data = await client.list_events()

    assert data["total"] == 0
    assert state == {"logins": 2, "requests": 2}


async def test_persistent_401_raises_auth_error(calls):
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/auth/login":
            return ok("token-1")
        return unauthorized()

    client = make_client(handler)
    with pytest.raises(AuthError):
        await client.list_events()


async def test_get_retries_on_connection_error():
    attempts = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/auth/login":
            return ok("token-1")
        attempts["n"] += 1
        if attempts["n"] == 1:
            raise httpx.ConnectError("boom", request=request)
        return ok({"records": [], "total": 0, "pages": 0})

    client = make_client(handler)
    await client.list_events()

    assert attempts["n"] == 2


async def test_write_without_idempotency_key_is_not_retried():
    attempts = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/auth/login":
            return ok("token-1")
        attempts["n"] += 1
        raise httpx.ConnectError("boom", request=request)

    client = make_client(handler)
    with pytest.raises(UpstreamError):
        await client.create_event({"productId": 7}, "")

    assert attempts["n"] == 1


async def test_write_with_idempotency_key_retries_once():
    attempts = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/auth/login":
            return ok("token-1")
        attempts["n"] += 1
        if attempts["n"] == 1:
            raise httpx.ConnectError("boom", request=request)
        return ok({"id": 9, "replayed": False})

    client = make_client(handler)
    data = await client.create_event({"productId": 7}, "thread:call")

    assert data["id"] == 9
    assert attempts["n"] == 2


async def test_idempotency_key_and_tool_name_are_sent(calls):
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/auth/login":
            return ok("token-1")
        calls.append(dict(request.headers))
        return ok({"dbStock": 60, "redisStock": 50, "mode": "increment"})

    client = make_client(handler)
    await client.stock_sync_increment(9, 10, "热度高", "thread:call")

    headers = calls[0]
    assert headers["idempotency-key"] == "thread:call"
    assert headers["x-tool-name"] == "adjust_seckill_stock"
