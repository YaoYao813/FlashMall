"""FlashMall Java 接口客户端。

只走网关 :8888，绝不直连 8080–8083（否则会绕过 JWT，X-User-Id 可伪造）。

三个必须处理的行为：
1. 业务失败是 HTTP 200 + body.code=500 —— 只看 HTTP 状态会漏掉全部业务错误；
2. 鉴权失败是真正的 HTTP 401 —— 重登一次再重试；
3. 写接口超时不能盲重试 —— 只有带幂等键时才能重试（Java 会回放）。
"""

import asyncio
import base64
import json
import logging
import time
from typing import Any

import httpx

from app.clients.errors import AuthError, BizError, UpstreamError
from app.config import settings

logger = logging.getLogger(__name__)

RETRY_DELAYS = (0.2, 0.5)
LOGIN_PATH = "/api/auth/login"
EVENTS_PATH = "/api/admin/seckill/events"


class FlashMallClient:
    def __init__(
        self,
        *,
        base_url: str | None = None,
        username: str | None = None,
        password: str | None = None,
        transport: httpx.AsyncBaseTransport | None = None,
        timeout: float = 10.0,
    ) -> None:
        self._base_url = (base_url or settings.flashmall_base_url).rstrip("/")
        self._username = username or settings.admin_username
        self._password = password or settings.admin_password.get_secret_value()
        self._http = httpx.AsyncClient(
            base_url=self._base_url,
            timeout=httpx.Timeout(timeout),
            transport=transport,
        )
        self._token: str | None = None
        self._token_expiry: float = 0.0
        self._login_lock = asyncio.Lock()

    async def aclose(self) -> None:
        await self._http.aclose()

    async def __aenter__(self) -> "FlashMallClient":  # noqa: PYI034
        return self

    async def __aexit__(self, *exc_info: object) -> None:
        await self.aclose()

    # ==================== 登录 ====================

    async def _ensure_token(self) -> str:
        if self._token and time.time() < self._token_expiry - 60:
            return self._token
        async with self._login_lock:
            if self._token and time.time() < self._token_expiry - 60:
                return self._token
            await self._login()
        if self._token is None:
            raise AuthError("管理员登录失败，请检查 ADMIN_USERNAME / ADMIN_PASSWORD")
        return self._token

    async def _login(self) -> None:
        payload = {"username": self._username, "password": self._password}
        try:
            response = await self._http.post(LOGIN_PATH, json=payload)
        except httpx.HTTPError as exc:
            raise UpstreamError(f"连接网关失败：{type(exc).__name__}") from exc
        try:
            body = response.json()
        except ValueError as exc:
            raise UpstreamError("登录接口返回了非 JSON 响应") from exc
        if response.status_code != 200 or body.get("code") != 200:
            message = body.get("message") or f"HTTP {response.status_code}"
            raise AuthError(f"管理员登录失败：{message}")
        token = body.get("data")
        if not isinstance(token, str) or not token:
            raise AuthError("登录接口没有返回 JWT")
        self._token = token
        self._token_expiry = self._jwt_expiry(token)

    @staticmethod
    def _jwt_expiry(token: str) -> float:
        try:
            payload = token.split(".")[1]
            payload += "=" * (-len(payload) % 4)
            data = json.loads(base64.urlsafe_b64decode(payload))
            return float(data["exp"])
        except Exception:  # noqa: BLE001 - 解析失败时保守按 1 小时算
            return time.time() + 3600

    # ==================== 请求 ====================

    async def _request(
        self,
        method: str,
        path: str,
        *,
        json_body: dict | None = None,
        params: dict | None = None,
        idempotency_key: str | None = None,
        tool_name: str | None = None,
        retries: int = 0,
    ) -> Any:
        attempt = 0
        relogged = False
        while True:
            try:
                token = await self._ensure_token()
                headers = {"Authorization": f"Bearer {token}"}
                if idempotency_key:
                    headers["Idempotency-Key"] = idempotency_key
                if tool_name:
                    headers["X-Tool-Name"] = tool_name
                response = await self._http.request(
                    method, path, json=json_body, params=params, headers=headers
                )
            except httpx.HTTPError as exc:
                if attempt < retries:
                    await asyncio.sleep(RETRY_DELAYS[min(attempt, len(RETRY_DELAYS) - 1)])
                    attempt += 1
                    continue
                raise UpstreamError(f"连接 Java 服务失败（{path}）：{type(exc).__name__}") from exc

            if response.status_code == 401:
                # 401 是真正的鉴权失败（网关直接写 JSON）；重登一次再试
                if not relogged:
                    relogged = True
                    self._token = None
                    self._token_expiry = 0.0
                    continue
                raise AuthError("管理员登录已失效，请检查 ADMIN_USERNAME / ADMIN_PASSWORD")

            if response.status_code != 200:
                raise UpstreamError(f"Java 服务返回 HTTP {response.status_code}（{path}）")

            try:
                body = response.json()
            except ValueError as exc:
                raise UpstreamError("Java 服务返回了非 JSON 响应") from exc

            code = body.get("code")
            if code == 200:
                return body.get("data")
            if code == 401:
                if not relogged:
                    relogged = True
                    self._token = None
                    self._token_expiry = 0.0
                    continue
                raise AuthError("管理员登录已失效，请检查 ADMIN_USERNAME / ADMIN_PASSWORD")

            # code=500：业务失败，message 直接给模型/用户看
            raise BizError(body.get("message") or "业务处理失败")

    # ==================== 业务方法 ====================

    async def list_events(
        self,
        status: str = "all",
        product_id: int | None = None,
        page: int = 1,
        size: int = 10,
    ) -> dict:
        params: dict[str, Any] = {"status": status, "page": page, "size": size}
        if product_id is not None:
            params["productId"] = product_id
        return await self._request("GET", EVENTS_PATH, params=params, retries=2)

    async def get_stats(self, event_id: int) -> dict:
        return await self._request("GET", f"{EVENTS_PATH}/{event_id}/stats", retries=2)

    async def create_event(self, payload: dict, idempotency_key: str) -> dict:
        return await self._request(
            "POST",
            EVENTS_PATH,
            json_body=payload,
            idempotency_key=idempotency_key,
            tool_name="create_seckill_event",
            retries=1 if idempotency_key else 0,
        )

    async def stock_sync_reset(self, event_id: int) -> dict:
        # reset 是 SET 语义，重复执行无害，可以安全重试
        return await self._request(
            "POST",
            f"{EVENTS_PATH}/{event_id}/stock-sync",
            json_body={"mode": "reset"},
            tool_name="create_seckill_event",
            retries=1,
        )

    async def stock_sync_increment(
        self,
        event_id: int,
        delta: int,
        reason: str,
        idempotency_key: str,
    ) -> dict:
        # 只有带幂等键才能重试；没有键时 Java 无法识别重复请求
        return await self._request(
            "POST",
            f"{EVENTS_PATH}/{event_id}/stock-sync",
            json_body={"mode": "increment", "delta": delta, "reason": reason},
            idempotency_key=idempotency_key,
            tool_name="adjust_seckill_stock",
            retries=1 if idempotency_key else 0,
        )


_client: FlashMallClient | None = None


def get_client() -> FlashMallClient:
    """MCP 工具共用一个客户端，复用 JWT 与连接池。"""
    global _client
    if _client is None:
        _client = FlashMallClient()
    return _client
