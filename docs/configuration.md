# 环境变量与配置

> 所有配置都能用环境变量覆盖，无需改代码。

## 切换模型供应商

都用环境变量覆盖即可，无需改代码：

| 变量 | 说明 | 默认值 |
|---|---|---|
| `AI_BASE_URL` | 兼容接口根地址（**不要带 `/v1`**） | `https://api.siliconflow.cn` |
| `AI_API_KEY` | API Key | `sk-change-me` |
| `AI_CHAT_MODEL` | 对话模型 | `deepseek-ai/DeepSeek-V3` |
| `AI_EMBEDDING_MODEL` | Embedding 模型 | `BAAI/bge-m3` |
| `AIKB_VECTOR_STORE` | 向量库实现：`memory` / `pgvector` | `memory` |
| `AIKB_RETRIEVAL_MODE` | 检索模式：`vector` / `hybrid` / `hybrid-rerank` | `hybrid-rerank` |
| `AIKB_AGENT_ENABLED` | 是否启用 Agent 工具调用接口 | `true` |
| `AIKB_AGENT_MAX_TOOL_CALLS` | 单轮最多允许的工具调用次数 | `6` |
| `AIKB_AGENT_MAX_ATTEMPTS` | 单个工具最多尝试几次（含首次） | `3` |
| `AIKB_AGENT_TOOL_TIMEOUT_MS` | 单次工具调用超时毫秒数；`<=0` 关闭超时 | `2000` |
| `AIKB_AGENT_PLANNER_ENABLED` | 是否启用规则式任务规划 | `true` |
| `AIKB_STREAM_TIMEOUT_MS` | 单条 SSE 连接的最长存活毫秒数；`<=0` 不设超时 | `300000` |
| `AIKB_OBSERVABILITY_ENABLED` | 是否累计模型调用用量与调用明细 | `true` |
| `AIKB_PRICE_PROMPT_PER_1M` | 每百万输入 token 的价格（示例值，务必按供应商覆盖） | `2.0` |
| `AIKB_PRICE_COMPLETION_PER_1M` | 每百万输出 token 的价格（示例值，务必按供应商覆盖） | `8.0` |
| `AIKB_MODEL_MAX_ATTEMPTS` | 模型调用最多尝试几次（含首次）；越小失败越快 | `2` |
| `AIKB_AI_USE_HTTP2` | 是否用 HTTP/2 访问模型网关；容器/WSL 下建议保持 `false` | `false` |
| `AIKB_AI_CONNECT_TIMEOUT_MS` | 模型 HTTP 连接超时毫秒数 | `5000` |
| `AIKB_AI_READ_TIMEOUT_MS` | 模型 HTTP 读取超时毫秒数（只作用于嵌入/非流式对话，流式不受影响） | `60000` |
| `AIKB_STREAM_IDLE_TIMEOUT_MS` | 流式输出两次内容之间的最长静默毫秒数；超时推 `error` 事件 | `90000` |
| `AIKB_DB_URL` | pgvector 的 JDBC 连接串 | `jdbc:postgresql://localhost:5432/aikb` |
| `AIKB_DB_USERNAME` / `AIKB_DB_PASSWORD` | pgvector 数据库账号 | `aikb` / `aikb` |

**改用 DeepSeek 官方 API 做对话时注意**：DeepSeek 官方**不提供 Embedding 接口**。
由于 Spring AI 的 OpenAI 客户端只接受一个 `base-url`，你需要在代码里单独定义一个指向 Embedding 服务的
`EmbeddingModel` Bean（换成 `spring.ai.openai.embedding.base-url` 支持的服务，或自定义 `OpenAiEmbeddingModel`）。
已把向量库与检索逻辑做好抽象，这里再抽一层 embedding 供应商即可，是个很好的「模型供应商解耦」练习点。

> 换 embedding 模型时记得同步改 `app.rag.embedding-dimensions`，否则 pgvector 里 `vector(N)` 的维度和实际向量对不上。
