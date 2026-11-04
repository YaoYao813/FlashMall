# agent-service

FlashMall 的 Python Agent 层（V1 进行中）。

- 编排：LangGraph 状态机
- 工具协议：MCP（`commerce-mcp` / `ops-mcp`）
- 与 Java 的边界：只走网关 HTTP，不直连数据库
- 接口约定：见 `contracts/tools.md`

范围与验收标准见仓库根 `docs/AGENT-SCOPE.md`。

## 本地运行

```bash
cd agent-service
python -m venv .venv
.venv/Scripts/activate        # Windows
pip install -e ".[dev]"
cp .env.example .env          # 填 key
uvicorn app.main:app --reload --port 8000
```
