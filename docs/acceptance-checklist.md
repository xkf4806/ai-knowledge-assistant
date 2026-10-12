# 验收清单

> 按能力分组的功能验收项，用来快速判断「这版是不是能跑」。

## RAG 最小闭环

- [ ] `mvn test` 通过
- [ ] `/api/ping` 返回 `UP`
- [ ] `/api/chat` 能正常对话
- [ ] `/api/rag/ingest` 能把 `sample-docs` 写入向量库
- [ ] `/api/rag/ask` 能基于文档作答，并返回来源；问文档外的问题会承认「无法回答」

## 文档解析与分块

- [ ] 支持 PDF、Word、Markdown 至少三种格式的解析
- [ ] `/api/rag/upload` 能上传并索引真实文档，返回 `documents / chunks / strategy / sources`
- [ ] `/api/rag/preview` 能对比 `fixed` / `heading` / `paragraph` 及不同 `chunkSize`、`overlap`
- [ ] 上传不支持的格式时返回 `400` 和清晰错误信息
- [ ] `mvn test` 通过（含 PDF / Word / Markdown 解析与分块用例）

## 检索工程化与引用溯源

- [ ] `docker compose up -d` 能起一个带 `vector` 扩展的 PostgreSQL
- [ ] `AIKB_VECTOR_STORE=pgvector` 启动时自动建出 `ai_kb_vectors` 表与 HNSW 余弦索引
- [ ] `/api/rag/status` 能报告当前向量库实现与检索参数
- [ ] `/api/rag/ask` 返回结构化 `sources`，`location` 能定位到「文档 · 小节 · 第几段」
- [ ] 同一 `sessionId` 的两次提问能借助历史理解指代（多轮对话）
- [ ] 重复导入同一文档不会产生重复片段（按来源先删后写）
- [ ] `mvn test` 通过（含会话记忆、检索来源组装、引用校验、状态接口用例）

## RAG 评测与检索优化

- [ ] `/api/rag/eval/dataset` 能返回 26 条评测问题、期望来源与标准答案
- [ ] `/api/rag/eval` 能一次输出 `vector` / `hybrid` / `hybrid-rerank` 的 Hit Rate、MRR、Precision@K
- [ ] `/api/rag/status` 能报告默认检索模式、候选数和 BM25 索引片段数
- [ ] `/api/rag/ask` 能用 `retrievalMode` 临时切换检索模式做对照
- [ ] 精确词项样例中，混合检索 + 重排能优先召回关键词候选
- [ ] `mvn test` 通过（含 BM25、重排链路、评测指标用例）

## Agent 工具调用

- [ ] `/api/agent/tools` 能列出工具名、说明与入参 JSON Schema
- [ ] 问「订单 A1001 能不能退」时，`toolsUsed` 里同时出现 `lookupOrder` 与 `searchKnowledgeBase`
- [ ] 问「SKU-2002 库存」时触发 `checkInventory`，问价格时触发 `quotePrice`
- [ ] `toolCalls` 能看到每次调用的入参、返回与耗时；工具查不到时如实返回「未查询到」
- [ ] 单轮工具调用超过 `app.agent.max-tool-calls` 时被拦截（状态为 `BLOCKED`）而不是无限循环
- [ ] 把 `app.agent.disabled-tools` 配上后，对应工具不再出现在 `/api/agent/tools` 也不可被调用
- [ ] `mvn test` 通过（含工具注册、记录装饰器、次数上限、业务工具与 Agent 接口用例）

## 多步规划与工具健壮性

- [ ] `/api/agent/plan` 能把复杂问题拆成有序步骤，并给出计划内工具（不调用模型、不花钱）
- [ ] 问「订单 A1001 能不能退」时计划为 2 步：`lookupOrder` + `searchKnowledgeBase`；问价格只暴露 `quotePrice`
- [ ] 默认只把计划内的工具交给模型（`app.agent.planner.restrict-tools=true`），识别不出任务时退回全部工具
- [ ] `/api/agent/tools` 能报告规划器开关、最大尝试次数与工具超时
- [ ] 工具偶发失败时按退避策略重试，`toolCalls[].attempts` 记录实际尝试次数
- [ ] 重试耗尽后返回结构化兜底提示（不抛异常、整轮请求不失败），状态为 `ERROR` / `TIMEOUT`，响应 `degraded=true`
- [ ] 用 `fault-injection-tools` 能现场复现「重试后成功」与「重试耗尽后兜底」
- [ ] `mvn test` 通过（含规划器、执行策略、重试/超时/兜底、按计划裁剪工具的用例）

## 流式输出、前端与部署

- [ ] 浏览器打开 `http://localhost:8080` 能看到对话界面，三种模式可切换
- [ ] 三个 `/stream` 接口返回 `text/event-stream`，事件顺序为 `meta → delta × N → done`
- [ ] 回答是逐段出现的（打字机效果），生成过程中「停止」按钮可用并真的能中断
- [ ] RAG 答案的 `[1]` 角标可点击，能定位并高亮对应的来源片段
- [ ] 智能助手模式能展开「调用详情」，看到计划步骤与每次工具调用的状态/耗时/重试次数
- [ ] 参数为空时仍是 400；推流开始后的失败用 `error` 事件下发，界面显示可读原因
- [ ] `/api/observability/usage` 能看到调用次数、tokens、估算费用与按入口/模型的分解
- [ ] 失败调用也会被记录（`failedCalls` 增加，明细里带错误原因）
- [ ] 刷新浏览器后对话记录、引用角标与本轮用量都还在，下一轮复用同一个 `sessionId`
- [ ] 上传 / 导入有等待计时与取消按钮；超时或取消后给出可读提示，不会一直转圈
- [ ] 容器重启后 `/api/rag/status` 的 `lexicalIndexSize` 仍等于库里片段总数（启动时自动重建关键词索引）
- [ ] `docker compose up -d --build` 一条命令拉起应用 + pgvector，应用使用持久化向量库
- [ ] `mvn test` 通过（含用量统计、观测接口、SSE 事件流与静态资源的用例）

## 文档与资产

- [ ] `docs/architecture.md` 的分层图能讲清一次提问的完整数据流（进来到返回）
- [ ] `/api/ping` 返回 `"status": "UP"`，前端顶栏显示「后端 UP」
- [x] `docs/assets/` 下有三张 PNG 架构图（总览 / 分层 / 部署），与 `docs/diagrams/` 下的 mermaid 源码一致
      （`node scripts/demo/render-diagrams.cjs` 可重新生成；文档里不再出现 mermaid 语法）
- [x] 有一版可播放的 Demo 视频：`docs/assets/demo.mp4`（由 `scripts/demo/record-demo.cjs` 真实录屏，
      覆盖知识库问答 + 引用 + 多轮追问 + 工具调用 + 基础对话对照 + 用量成本；
      录前自检 + 逐题校验 + 录后校验，成片里 `failedCalls = 0`、没有任何失败画面）
- [x] README 里有动态效果图：`docs/assets/demo.gif`（32 秒高光循环，约 3.6 MB）
