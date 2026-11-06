"""客户端错误类型：MCP 工具统一把它们转成 {"ok": false, "error": {...}}。"""


class FlashMallError(Exception):
    """所有上游错误的基类。message 是写给模型/用户看的中文整句。"""

    code = "UPSTREAM"

    def __init__(self, message: str, code: str | None = None) -> None:
        super().__init__(message)
        self.message = message
        if code is not None:
            self.code = code

    def to_dict(self) -> dict:
        return {"ok": False, "error": {"code": self.code, "message": self.message}}


class BizError(FlashMallError):
    """Java 返回 HTTP 200 + body.code=500 的业务失败（库存不足、活动不存在等）。"""

    code = "BIZ"


class AuthError(FlashMallError):
    """重登之后仍是 401。"""

    code = "AUTH"


class UpstreamError(FlashMallError):
    """网络、超时、非 JSON、5xx 等基础设施问题。"""

    code = "UPSTREAM"
