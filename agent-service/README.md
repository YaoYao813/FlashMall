# agent-service

FlashMall 的 Python Agent 层（V1 进行中）。

- 编排：LangGraph 状态机
- 工具协议：MCP（`commerce-mcp` / `ops-mcp`）
- 与 Java 的边界：只走网关 HTTP，不直连数据库
- 接口约定：见 `contracts/tools.md`

范围与验收标准见仓库根 `docs/AGENT-SCOPE.md`。

## 本地运行

前置：仓库根目录 `docker compose up -d`（含给 Agent 单独起的 `redis-stack`，宿主 **6381**；
LangGraph checkpointer 需要 RedisJSON/RediSearch，且**只能用 db 0**）。

```bash
cd agent-service
python -m venv .venv
.venv/Scripts/activate        # Windows
pip install -e ".[dev]"
cp .env.example .env          # 填 LLM_API_KEY / ADMIN_USERNAME / ADMIN_PASSWORD

# 先跑命令行验证图（读写都能测）
python -m app.cli

# 再起网页（http://127.0.0.1:8000）
uvicorn app.main:app --port 8000
```

测试与验收：

```bash
pytest -q
.venv/Scripts/python ../scripts/acceptance_m4.py
```
