# 常见问题

> 启动、调用、检索、Agent、部署相关的常见问题与排查思路。

- **启动报 API Key 错误**：确认 `AI_API_KEY` 已设置（如 `export AI_API_KEY=sk-...`），且重启了应用。
- **调用报 401/403**：Key 无效或额度不足。
- **调用报 404 或模型不存在**：`AI_CHAT_MODEL` / `AI_EMBEDDING_MODEL` 与所用网关的模型名不匹配。
- **重启后检索不到内容**：默认内存向量库重启即清空，需要重新调用 `/api/rag/ingest`；改用 `AIKB_VECTOR_STORE=pgvector` 后索引会持久化到 PostgreSQL。
- **切到 pgvector 启动报连接错误**：确认 `docker compose up -d` 已启动、`AIKB_DB_URL` 指向的库可连；容器健康检查用 `docker compose ps` 查看。
- **pgvector 报维度不匹配**：`app.rag.embedding-dimensions` 必须与 embedding 模型实际维度一致（bge-m3 是 1024）；换模型要同步改并重建表。
- **多轮对话没记住上下文**：检查第二次请求是否带上了第一次响应返回的 `sessionId`；记忆默认只留最近 10 条消息。
- **上传报「不支持的文档格式」**：检查扩展名是否在支持列表中；扫描件 PDF 只有图片、抽不出文字，需要 OCR（暂未接入）。
- **上传报「解析文档失败」**：文档可能损坏或加密，先用阅读器确认能正常打开。
- **分块预览结果为空**：文档抽出的文本可能为空，或该格式扫描件没有文字层。
- **Agent 只回答不调工具**：先确认 `/api/agent/tools` 能列出工具；再换更明确的问题（带上「订单 A1001」「SKU-2002」这类参数），不同模型主动调工具的意愿差别很大。
- **回答里没有引用知识库来源**：确认已经调用过 `/api/rag/ingest`；内存向量库重启后为空，`searchKnowledgeBase` 会返回「没有检索到」。
- **`toolCalls` 里出现 `BLOCKED`**：单轮工具调用达到 `AIKB_AGENT_MAX_TOOL_CALLS` 上限被拦截，通常说明问题过于宽泛或模型陷入循环，可以把问题拆小。
- **`/api/agent/plan` 返回 `planned:false`**：规划器没识别出任务类型（例如寒暄），这时会退回「全部工具交给模型」；按规划规则补上「订单 / 退货 / 库存 / 价格 / 政策」这类关键词即可命中。
- **模型看不到某个工具**：默认只暴露计划内的工具。想让它看全部，把 `app.agent.planner.restrict-tools` 设为 `false`，或把 `app.agent.planner.enabled` 设为 `false` 关掉规划。
- **`toolCalls[].status` 是 `ERROR` / `TIMEOUT`**：工具重试耗尽后走了兜底分支，`error` 字段是最后一次失败原因；响应 `degraded=true` 表示本轮结果降级但仍给出了答复。
- **想调大重试次数或超时**：改 `app.agent.resilience.max-attempts` / `timeout-ms`；超时设成 `<=0` 表示关闭超时（在调用线程直接执行）。
- **测试里想跳过退避等待**：`ToolExecutionPolicy` 的包内构造函数支持注入「睡眠器」，单元测试用 `TestPolicies` 传入空实现即可。
- **流式没反应 / 一次性全出来**：中间有反向代理时记得关缓冲（Nginx 要 `proxy_buffering off`）；本地直连 Tomcat 不会缓冲。
- **`/stream` 返回 400 或 500 而不是事件流**：说明失败发生在推流之前（参数为空，或 RAG 检索阶段的 embedding 调用失败），
  这类错误按 HTTP 语义返回；推流开始之后的失败才会用 `error` 事件。
- **用量面板一直是 0**：确认 `AIKB_OBSERVABILITY_ENABLED` 没被关掉；再确认请求确实调到了模型（失败调用也会计一条，但 token 为 0）。
- **估算费用明显不对**：默认单价只是示例值，改 `AIKB_PRICE_PROMPT_PER_1M` / `AIKB_PRICE_COMPLETION_PER_1M`；
  token 数是网关实测，费用只是按单价估算。
- **界面按钮没有图标**：图标走 lucide 的 CDN，内网离线时会缺图标，功能不受影响。
- **`docker compose up` 起不来应用**：先确认 `.env` 里的 `AI_API_KEY`（没配也能起，只是问答会返回 401）；
  再确认 8080 端口没被占用（本地 IDE 跑着应用时也会占用）。
- **上传文档感觉「卡住」**：embedding 接口慢或不通时会重试，现在最多 2 次、每次读取 60 秒，前端另有 180 秒上限与「取消」。
  想更激进可以把 `AIKB_MODEL_MAX_ATTEMPTS` 设成 1、把 `AIKB_AI_READ_TIMEOUT_MS` 调小。
- **日志里出现 `Failed to validate connection ... (This connection has been closed)`**：这是 Hikari 换掉了一个已经死掉的空闲连接，
  请求本身仍会成功（常见于数据库容器重启之后）。已把 `max-lifetime` 压到 5 分钟并开启 2 分钟保活，正常情况下不会再出现。
- **重启后关键词检索变差 / `lexicalIndexSize` 为 0**：向量数据在 pgvector 里，关键词索引是内存态；
  应用启动会自动从向量库重建，日志里能看到 `已从向量库重建关键词索引：N 个来源、M 个片段`。
  若这里打了 WARN，检查数据库连接即可，向量检索仍然可用。
- **刷新浏览器后对话还在，但模型「不记得」上下文**：界面记录来自 localStorage，上下文来自后端的会话记忆；
  后端的记忆是进程内的，重启应用后同一个 `sessionId` 会变成一段空历史（界面仍显示旧记录）。想彻底对齐点「新会话」重开。
- **流式问答只有第一帧 `meta`、正文一直不出来**：如果非流式接口（`/api/chat`、`/api/rag/ask`）正常而流式卡住，
  基本就是 HTTP/2 长连接在这条网络链路上卡死了。模型客户端默认已固定 HTTP/1.1（`AIKB_AI_USE_HTTP2=false`），
  只有当你的网关必须走 HTTP/2 时才打开它。另外流式输出有 90 秒静默兜底
  （`AIKB_STREAM_IDLE_TIMEOUT_MS`），超时会推一条 `error` 事件说明原因，而不是一直转圈。
- **回答结束但界面上只有「模型这次没有返回内容」**：说明网关这一轮流式响应是空的（实测硅基流动偶发：
  消耗了 token，`delta.content` 却是 `""` 然后直接 `finish_reason=stop`）。应用已经自动改用非流式重跑同一轮
  （Agent 模式连工具调用一起重跑），正常情况下你会直接看到答案；如果兜底也没拿到内容，会提示稍后再问一次。
  用量面板里能看到这种「一次失败空流 + 一次成功重跑」的记录，方便确认是网关侧的偶发问题。
