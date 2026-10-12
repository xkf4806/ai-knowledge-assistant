# RAG：文档解析、分块、检索与评测

> 覆盖 RAG 全链路：多格式解析 → 可配置分块 → 向量化 → 混合检索与重排 → 带引用的生成 → 评测与优化。
> 评测的原始数据与指标口径见 [rag-evaluation.md](rag-evaluation.md)；架构与模块划分见 [architecture.md](architecture.md)。

## 文档解析与分块（Chunking）

### 1. 多格式解析

`rag/parser` 用 Apache Tika 统一解析：上传后由 Tika 自动探测格式并抽取纯文本，
当前支持 `pdf / doc / docx / md / markdown / txt / html / htm / csv / xls / xlsx / ppt / pptx`。
不支持的格式、损坏或加密文档会返回 **400** 和可读的错误信息，而不是 500 堆栈。

### 2. 三种分块策略

| 策略 | 做法 | 适用场景 |
|---|---|---|
| `fixed` | 按固定字符数切分，相邻片段保留重叠窗口 | 无结构的长文本 |
| `heading` | 先按 Markdown / 文本标题切成小节，超长小节再按固定长度切分；正文前带上标题路径 | 有标题层级的文档（默认） |
| `paragraph` | 按空行分段，把相邻小段落合并到目标长度 | 段落清晰的说明文 |

`heading` 策略会把标题路径（如 `员工手册 > 年假`）写进片段正文和元数据，
检索命中时 `sources` 里也能看到 `heading`，为引用溯源打基础。

### 3. 用 preview 做对比（验收要求）

`/api/rag/preview` 只做解析 + 分块，不调用 embedding，可以反复调参看效果：

```bash
# 一次对比三种策略
curl -X POST http://localhost:8080/api/rag/preview \
  -F "file=@/path/to/员工手册.docx" -F "strategies=fixed,heading,paragraph"

# 固定长度策略下对比不同 chunkSize
curl -X POST http://localhost:8080/api/rag/preview \
  -F "file=@/path/to/员工手册.docx" -F "strategies=fixed" -F "chunkSize=150" -F "overlap=30"
```

返回里每个策略含 `chunkCount` 与逐条 `chunks`（`index` / `heading` / `length` / `preview`），
能直接看出切分粒度、边界和是否切断语义。

## 向量检索工程化 + 引用溯源 + 多轮对话

解决的是「能不能用」，解决的是「像不像一个工程」：数据能持久化、答案能追溯、对话有记忆。

### 1. 持久化向量库：内存 → pgvector

`RagConfig` 按 `app.rag.vector-store` 声明向量库 Bean：

| 值 | 实现 | 特点 |
|---|---|---|
| `memory`（默认） | `SimpleVectorStore` | 进程内存，重启即丢，无需数据库，适合跑测试 |
| `pgvector` | `PgVectorStore` | 落 PostgreSQL，重启不丢，支持 HNSW 近似检索 |

- 两种实现都只暴露 Spring AI 的 `VectorStore` 接口，上层 `RetrievalService` / `IngestionService` 无感切换。
- `PgVectorStore` 显式指定 `dimensions=1024`，启动阶段不调用 embedding 接口，避免「启动即花钱」。
- `docker-compose.yml` 用 `pgvector/pgvector:pg16` 镜像，自带 `vector` 扩展，`initializeSchema` 会自动建表建索引。

### 2. 引用溯源：答案能落到「第几段」

索引时每个片段都带 `source` / `chunk` / `heading` 元数据；检索时 `RetrievalService` 把它组装成 `SourceRef`：

- 上下文里给每段资料编号：`[1]（来源：员工手册.md · 员工手册 > 年假 · 第 2 段）`；
- 系统提示词要求模型在句末用 `[1]、[2]` 标注引用；
- 返回结构里 `sources[].location` 直接给出「哪份文档 · 哪一节 · 第几段」，前端可据此把角标映射回原文；
- `RagService` 会解析答案里的引用编号并校验范围，越界编号视为幻觉引用并记 WARN 日志，方便做量化监测。

### 3. 多轮对话：滑动窗口记忆

`ConversationMemoryService` 基于 Spring AI 的 `MessageWindowChatMemory`：

- 每个 `sessionId` 维护一条消息列表，一问一答成对写入；
- 只保留最近 `app.chat.memory.max-messages` 条（默认 10，即 5 轮），历史不会无限增长、token 成本可控；
- `/api/rag/ask` 与 `/api/chat` 共用同一份记忆，可跨接口延续上下文；
- 返回结构带 `sessionId` 与 `turns`，`DELETE .../sessions/{id}` 可清空会话。

### 4. 重复导入不再叠加

`IngestionService` 在写入前先按 `source` 删除旧片段（元数据过滤删除），
避免同一份文档被索引多遍后检索结果里出现大量重复内容。

## RAG 评测与优化

这一部分回答「怎么用数据证明它变好了」。下面用同一个评测集对比三档检索模式：

- `vector`：只走向量检索，保留为优化前基线；
- `hybrid`：向量召回 + BM25 关键词召回，用 RRF 融合两路排名；
- `hybrid-rerank`：在混合召回候选上，用加权 RRF 排序，再用问题词覆盖率和标题匹配度做微调；
- 26 条评测问题：覆盖员工手册、退款政策、IT 支持，记录期望文档、期望标题和标准答案；
- 检索指标：Hit Rate、MRR、Precision@K；可选答案准确率与引用准确率。

### 1. 为什么加 BM25

向量检索擅长语义相近，但订单号、产品名、数字条件和专有名词可能被泛化掉。
BM25 对精确词项更敏感，两路候选合并后再排序，能补足单一向量检索的盲区。

`Bm25LexicalIndex` 没有额外引入分词服务：中文连续文本按“单字 + 相邻二元词”切分，
英文和数字按单词切分，再用标准 BM25 公式打分。它更适合作为可解释、可测试的轻量实现。

### 2. 两阶段检索

`RetrievalService` 的 `hybrid-rerank` 流程：

1. 向量检索和 BM25 各取 `candidate-k` 个候选；
2. 按 `source + chunk` 合并去重，保留两路排名与原始分数；
3. `RerankService` 用加权 RRF 合并两路排名，再加入问题词覆盖率和标题匹配度做微小 tie-break；
4. 截断到 `top-k`，写回 `SourceRef` 的来源、分数和检索模式。

默认权重与候选数都在 `application.yml` 的 `app.rag.*` 下，可以按评测结果调参。
本次样例集实测：检索指标三档均打满，完整答案准确率从 `76.92%` 提升到 `84.62%`，引用准确率保持 `100%`。

### 3. 评测接口

评测集在 [src/main/resources/evaluation/rag-eval.json](../src/main/resources/evaluation/rag-eval.json)。

```bash
# 只跑检索指标，默认对比 vector / hybrid / hybrid-rerank
curl -X POST http://localhost:8080/api/rag/eval \
  -H "Content-Type: application/json" \
  -d '{"modes":["vector","hybrid","hybrid-rerank"],"includeAnswers":false}'

# 显式开启完整答案评测（会调用对话模型）
curl -X POST http://localhost:8080/api/rag/eval \
  -H "Content-Type: application/json" \
  -d '{"modes":["vector","hybrid-rerank"],"includeAnswers":true}'
```

`includeAnswers=false` 时只计算检索指标；`includeAnswers=true` 时会检查答案是否覆盖标准关键事实，
以及引用来源是否正确。优化前后结果填到 [`docs/rag-evaluation.md`](rag-evaluation.md)。

## 快速体验

### （可选）用 pgvector 做持久化检索

默认是内存向量库，重启就丢。想做「重启后索引还在」的持久化演示，切到 pgvector：

```bash
# 1. 起一个带 pgvector 扩展的 PostgreSQL（仓库根目录）
docker compose up -d pgvector

# 2. 用 pgvector 实现启动应用（也可以改 application.yml）
AIKB_VECTOR_STORE=pgvector ./build.sh spring-boot:run
```

> 只想起数据库（本地用 IDE 跑应用）就用上面这条；想连应用一起起，见「」的
> `docker compose up -d --build`。

连接信息默认是 `jdbc:postgresql://localhost:5432/aikb`，用户/密码都是 `aikb`，
可用 `AIKB_DB_URL` / `AIKB_DB_USERNAME` / `AIKB_DB_PASSWORD` 覆盖。
启动后 `PgVectorStore` 会自动建表（`ai_kb_vectors`，含 `vector(1024)` 列与 HNSW 余弦索引），
索引数据落在数据库里，重启应用不会丢。

确认当前用的是哪个实现（不调用模型、不花钱）：

```bash
curl http://localhost:8080/api/rag/status
# {"vectorStore":"pgvector","retrievalMode":"hybrid-rerank","candidateK":12,"lexicalIndexSize":11,...}
```

### 多轮对话与引用溯源

第一次提问不带 `sessionId`，响应里会返回一个；下次带上它即可续上上下文：

```bash
# 第一轮
curl -X POST http://localhost:8080/api/rag/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"无理由退货的期限是几天？"}'
# 响应里拿到 sessionId，例如 "3f1c..."

# 第二轮用指代追问，靠会话记忆理解「它」
curl -X POST http://localhost:8080/api/rag/ask \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"3f1c...","question":"那运费谁承担？"}'
```

响应里的 `sources` 是结构化引用（`label` / `source` / `chunk` / `heading` / `score` / `location` / `snippet`），
`location` 直接给出「哪份文档 · 哪一节 · 第几段」，`citations` 是答案里实际用到的引用编号。
只想在某一篇文档里检索时，加 `source`：

```bash
curl -X POST http://localhost:8080/api/rag/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"退货期限是几天？","source":"refund-policy.md"}'
```

本次提问也可以临时覆盖检索模式，方便对比同一问题的召回差异：

```bash
curl -X POST http://localhost:8080/api/rag/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"VPN 账号有效期是多少天？","retrievalMode":"vector"}'
```

### RAG 评测与优化前后对比

先导入示例文档，再跑评测。默认一次对比三种检索模式，不调用对话模型：

```bash
curl -X POST http://localhost:8080/api/rag/ingest
./scripts/evaluate-rag.sh
```

Windows PowerShell：

```powershell
Invoke-RestMethod -Method Post http://localhost:8080/api/rag/ingest | Out-Null
.\scripts\evaluate-rag.ps1
```

返回每种模式的 `hitRate`、`mrr`、`precisionAtK` 与逐题明细。需要把答案质量一起纳入评测时：

```bash
AIKB_EVAL_ANSWERS=true ./scripts/evaluate-rag.sh
```

> 开启 `includeAnswers` 后会逐题调用对话模型，产生真实费用；默认只跑检索指标。

评测口径、结果表和复盘模板见 [`docs/rag-evaluation.md`](rag-evaluation.md)。
