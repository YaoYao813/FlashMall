"""运行配置：从 agent-service/.env 读取，导入即校验，缺配置直接失败。

为什么不用 os.getenv：
- 类型转换和缺失字段会给出明确报错，而不是运行时 None；
- SecretStr 保证日志/异常里不会出现明文密钥；
- IDE 能补全字段，.env.example 与代码字段一一对应。
"""

from functools import lru_cache
from pathlib import Path
from urllib.parse import urlparse

from pydantic import SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

ENV_FILE = Path(__file__).resolve().parent.parent / ".env"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=ENV_FILE,
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # MCP 工具和只读接口不需要 LLM；key 留空也能起服务，
    # 只有构建图（router）时才强制要求，见 require_llm_api_key()
    llm_api_key: SecretStr = SecretStr("")
    llm_base_url: str = "https://api.deepseek.com/v1"
    llm_model: str = "deepseek-chat"

    flashmall_base_url: str = "http://localhost:8888"
    admin_username: str
    admin_password: SecretStr

    # Agent 的 checkpointer 用独立 redis-stack（6381），与 Java 秒杀的 6380 隔离。
    # 必须是 db 0：RediSearch 不允许在非 0 库建索引（会报 Cannot create index on db != 0）
    redis_url: str = "redis://localhost:6381/0"

    @field_validator("admin_password")
    @classmethod
    def _not_blank(cls, value: SecretStr) -> SecretStr:
        if not value.get_secret_value().strip():
            raise ValueError("不能为空，请在 agent-service/.env 中填写")
        return value

    @field_validator("llm_base_url", "flashmall_base_url")
    @classmethod
    def _strip_trailing_slash(cls, value: str) -> str:
        return value.rstrip("/")

    @field_validator("redis_url")
    @classmethod
    def _check_redis(cls, value: str) -> str:
        parsed = urlparse(value)
        if parsed.scheme != "redis":
            raise ValueError("必须是 redis:// 开头的 URL")
        if parsed.port != 6381:
            raise ValueError(
                "端口必须是 6381（Agent 专用 redis-stack）；6380 是 Java 秒杀链路的 Redis"
            )
        return value

    def require_llm_api_key(self) -> str:
        """图构建前调用；缺 key 在这里报明确错误，而不是第一次对话才失败。"""
        key = self.llm_api_key.get_secret_value().strip()
        if not key:
            raise RuntimeError(
                "缺少 LLM_API_KEY：请在 agent-service/.env 中填写后重新启动 Agent 服务"
            )
        return key


@lru_cache
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
