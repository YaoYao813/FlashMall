"""测试环境变量：在导入 app.config 之前填好必填项。

真实的 .env 里 LLM_API_KEY 由用户自己填；测试用假值，避免测试依赖真实密钥。
环境变量优先级高于 .env，所以这里覆盖不会写进用户文件。
"""

import os

os.environ.setdefault("LLM_API_KEY", "test-key")
os.environ.setdefault("ADMIN_USERNAME", "test-admin")
os.environ.setdefault("ADMIN_PASSWORD", "test-password")
os.environ.setdefault("REDIS_URL", "redis://localhost:6381/0")
os.environ.setdefault("FLASHMALL_BASE_URL", "http://gateway.test")
