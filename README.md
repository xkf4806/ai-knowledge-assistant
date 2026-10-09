# 企业知识库智能助手（第 1–3 周：最小 RAG 闭环 + 文档解析与分块 + 向量检索工程化）

这是 8 周求职路线图的第 1–3 周产物：一个能跑通的 **Spring AI + RAG** 后端骨架。
第 1 周先把「文档 → 向量 → 检索 → 大模型生成」这条链路跑起来，看清 RAG 的每一步；
第 2 周补上**真实文档解析**与**可配置分块策略**，让系统不再只能吃手写的示例文本；
第 3 周做**向量检索工程化**：接入 pgvector 持久化、给答案加**引用溯源**、给对话加**多轮记忆**。

## 技术栈

| 组件 | 版本 / 选型 |
|---|---|
| JDK | 17 |
| 构建 | Maven |
| 框架 | Spring Boot 3.5.15 |
| AI 框架 | Spring AI 1.1.8 |
| 模型接入 | OpenAI 兼容接口（默认网关内置 DeepSeek 对话模型） |
| 向量库 | `memory`（`SimpleVectorStore`，默认）/ `pgvector`（持久化，第 3 周新增） |
| 文档解析 | Apache Tika 3.2.3（PDF / Word / Markdown / HTML / txt 等） |
| 分块策略 | `fixed` / `heading` / `paragraph`，参数可配置 |
| 会话记忆 | Spring AI `ChatMemory` + `MessageWindowChatMemory`（第 3 周新增，滑动窗口） |

## 目录结构

```
src/main/java/com/example/aikb
├── AiKnowledgeAssistantApplication.java   # 启动类
├── chat/                                  # 基础对话 + 多轮记忆
│   ├── ChatController.java
│   ├── ChatRequest.java / ChatResponse.java
│   ├── ChatMemoryProperties.java          # 第 3 周：app.chat.memory.* 配置
│   └── ConversationMemoryService.java     # 第 3 周：会话记忆（滑动窗口）
├── config/RagConfig.java                  # 按配置声明 memory / pgvector 向量库 Bean
├── rag/                                   # RAG 核心
│   ├── RagController.java                 # /ingest、/upload、/preview、/ask、/status、/sessions
│   ├── RagService.java                    # 检索 + 编号上下文 + 生成 + 引用校验 + 记忆写入
│   ├── RetrievalService.java              # 第 3 周：向量检索 + 元数据过滤 + 来源组装
│   ├── RetrievedChunk.java / SourceRef.java  # 第 3 周：带来源/分数/定位的命中片段
│   ├── IngestionService.java              # 解析 -> 分块 -> 按来源先删后写向量库；preview 只分块
│   ├── IngestResponse.java                # 含 documents / chunks / strategy / sources
│   ├── PreviewResponse.java / ChunkPreview.java / StrategyPreview.java
│   ├── SimpleTextSplitter.java            # 固定长度 + 重叠的底层切分（第 1 周）
│   ├── RagProperties.java                 # app.rag.* 配置（含 vector-store / dimensions）
│   ├── VectorStoreType.java               # 第 3 周：memory / pgvector
│   ├── parser/                            # 第 2 周：多格式文档解析
│   │   ├── DocumentParser.java            # 解析接口，便于替换/扩展
│   │   ├── TikaDocumentParser.java        # Tika 实现：PDF / Word / Markdown / HTML / txt
│   │   ├── ParsedDocument.java
│   │   └── DocumentParseException.java / UnsupportedDocumentException.java
│   └── splitter/                          # 第 2 周：可配置分块
│       ├── ChunkingStrategy.java          # fixed / heading / paragraph
│       ├── ChunkingOptions.java           # 策略 + chunkSize + overlap
│       ├── TextChunk.java                 # 片段文本 + 标题路径
│       └── TextSplitter.java              # 三种策略实现
└── web/                                   # HTTP 层
    ├── PingController.java                # /api/ping 健康检查
    └── ApiExceptionHandler.java           # 解析/参数错误 -> 400
src/main/resources
├── application.yml                        # 模型、RAG、上传大小配置
├── prompts/system-prompt.txt              # 系统提示词（UTF-8，独立于源码）
└── sample-docs/                           # 三个示例文档（Markdown + txt）
docker-compose.yml                         # 第 3 周：一键起 pgvector（PostgreSQL + vector 扩展）
```

## 快速开始

### 1. 准备 API Key

默认配置使用一个同时提供**对话**与 **Embedding** 的 OpenAI 兼容网关，默认对话模型就是 DeepSeek。
去[硅基流动](https://siliconflow.cn)注册并创建 API Key（用一个 Key 即可跑通全链路）。

> 想换成 DeepSeek 官方 API？见下面「切换模型供应商」。

### 2. 配置环境变量

```bash
export AI_API_KEY="sk-你的真实key"
```

也可以直接用命令行参数或修改 `application.yml`（不建议把 Key 提交到 Git）。

### 3. 启动

```bash
./build.sh spring-boot:run
```

看到 `Started AiKnowledgeAssistantApplication` 即启动成功，默认端口 `8080`。

### 4. 验证

先测健康检查（不需要 Key）：

```bash
curl http://localhost:8080/api/ping
```

再测基础对话连通性：

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"用一句话介绍你自己"}'
```

导入示例知识库（这一步会调用 Embedding 接口）：

```bash
curl -X POST http://localhost:8080/api/rag/ingest
```

基于知识库提问：

```bash
curl -X POST http://localhost:8080/api/rag/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"无理由退货的期限是几天？运费谁承担？"}'
```

返回里 `answer` 是答案，`sources` 是命中的文档片段来源。

上传一份自己的文档（PDF / Word / Markdown / txt 等）并索引：

```bash
curl -X POST http://localhost:8080/api/rag/upload \
  -F "file=@/path/to/你的文档.pdf" -F "strategy=heading"
```

只想看看不同分块策略怎么切？用 `preview`，它**不调用 embedding、不花钱**：

```bash
curl -X POST http://localhost:8080/api/rag/preview \
  -F "file=@/path/to/你的文档.pdf" -F "strategies=fixed,heading,paragraph" -F "chunkSize=200"
```

### 5.（可选）用 pgvector 做持久化检索（第 3 周）

默认是内存向量库，重启就丢。想做「重启后索引还在」的持久化演示，切到 pgvector：

```bash
# 1. 起一个带 pgvector 扩展的 PostgreSQL（仓库根目录）
docker compose up -d

# 2. 用 pgvector 实现启动应用（也可以改 application.yml）
AIKB_VECTOR_STORE=pgvector ./build.sh spring-boot:run
```

连接信息默认是 `jdbc:postgresql://localhost:5432/aikb`，用户/密码都是 `aikb`，
可用 `AIKB_DB_URL` / `AIKB_DB_USERNAME` / `AIKB_DB_PASSWORD` 覆盖。
启动后 `PgVectorStore` 会自动建表（`ai_kb_vectors`，含 `vector(1024)` 列与 HNSW 余弦索引），
索引数据落在数据库里，重启应用不会丢。

确认当前用的是哪个实现（不调用模型、不花钱）：

```bash
curl http://localhost:8080/api/rag/status
# {"vectorStore":"pgvector","vectorTable":"ai_kb_vectors","embeddingDimensions":1024,...}
```

### 6. 多轮对话与引用溯源（第 3 周）

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

## 接口一览

| 方法 | 路径 | 说明 | 需要 Key |
|---|---|---|---|
| GET | `/api/ping` | 健康检查 | 否 |
| POST | `/api/chat` | 基础对话（无知识库），支持 `sessionId` 多轮 | 是 |
| DELETE | `/api/chat/sessions/{sessionId}` | 清空某个会话的记忆 | 否 |
| POST | `/api/rag/ingest` | 导入示例文档到向量库，可传 `strategy` / `chunkSize` / `overlap` | 是 |
| POST | `/api/rag/upload` | 上传 PDF / Word / Markdown 等文档，解析 + 分块 + 索引 | 是 |
| POST | `/api/rag/preview` | 解析 + 分块预览，可一次对比多种策略与参数 | 否 |
| POST | `/api/rag/ask` | 基于知识库提问，支持 `sessionId` 多轮与 `source` 元数据过滤 | 是 |
| DELETE | `/api/rag/sessions/{sessionId}` | 清空某个会话的对话记忆 | 否 |
| GET | `/api/rag/status` | 当前向量库/检索/记忆参数 | 否 |

## 第 2 周：文档解析与分块（Chunking）

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
检索命中时 `sources` 里也能看到 `heading`，为第 3 周的引用溯源打基础。

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

## 第 3 周：向量检索工程化 + 引用溯源 + 多轮对话

第 1–2 周解决的是「能不能用」，第 3 周解决的是「像不像一个工程」：数据能持久化、答案能追溯、对话有记忆。

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
- `RagService` 会解析答案里的引用编号并校验范围，越界编号视为幻觉引用并记 WARN 日志，方便第 4 周做量化监测。

### 3. 多轮对话：滑动窗口记忆

`ConversationMemoryService` 基于 Spring AI 的 `MessageWindowChatMemory`：

- 每个 `sessionId` 维护一条消息列表，一问一答成对写入；
- 只保留最近 `app.chat.memory.max-messages` 条（默认 10，即 5 轮），历史不会无限增长、token 成本可控；
- `/api/rag/ask` 与 `/api/chat` 共用同一份记忆，可跨接口延续上下文；
- 返回结构带 `sessionId` 与 `turns`，`DELETE .../sessions/{id}` 可清空会话。

### 4. 重复导入不再叠加

`IngestionService` 在写入前先按 `source` 删除旧片段（元数据过滤删除），
避免同一份文档被索引多遍后检索结果里出现大量重复内容。

## 切换模型供应商

都用环境变量覆盖即可，无需改代码：

| 变量 | 说明 | 默认值 |
|---|---|---|
| `AI_BASE_URL` | 兼容接口根地址（**不要带 `/v1`**） | `https://api.siliconflow.cn` |
| `AI_API_KEY` | API Key | `sk-change-me` |
| `AI_CHAT_MODEL` | 对话模型 | `deepseek-ai/DeepSeek-V3` |
| `AI_EMBEDDING_MODEL` | Embedding 模型 | `BAAI/bge-m3` |
| `AIKB_VECTOR_STORE` | 向量库实现：`memory` / `pgvector` | `memory` |
| `AIKB_DB_URL` | pgvector 的 JDBC 连接串 | `jdbc:postgresql://localhost:5432/aikb` |
| `AIKB_DB_USERNAME` / `AIKB_DB_PASSWORD` | pgvector 数据库账号 | `aikb` / `aikb` |

**改用 DeepSeek 官方 API 做对话时注意**：DeepSeek 官方**不提供 Embedding 接口**。
由于 Spring AI 的 OpenAI 客户端只接受一个 `base-url`，你需要在代码里单独定义一个指向 Embedding 服务的
`EmbeddingModel` Bean（换成 `spring.ai.openai.embedding.base-url` 支持的服务，或自定义 `OpenAiEmbeddingModel`）。
第 3 周已把向量库与检索逻辑做好抽象，这里再抽一层 embedding 供应商即可，是个很好的「模型供应商解耦」练习点。

> 换 embedding 模型时记得同步改 `app.rag.embedding-dimensions`，否则 pgvector 里 `vector(N)` 的维度和实际向量对不上。

## 验收清单

### 第 1 周

- [ ] `mvn test` 通过
- [ ] `/api/ping` 返回 `UP`
- [ ] `/api/chat` 能正常对话
- [ ] `/api/rag/ingest` 能把 `sample-docs` 写入向量库
- [ ] `/api/rag/ask` 能基于文档作答，并返回来源；问文档外的问题会承认「无法回答」

### 第 2 周

- [ ] 支持 PDF、Word、Markdown 至少三种格式的解析
- [ ] `/api/rag/upload` 能上传并索引真实文档，返回 `documents / chunks / strategy / sources`
- [ ] `/api/rag/preview` 能对比 `fixed` / `heading` / `paragraph` 及不同 `chunkSize`、`overlap`
- [ ] 上传不支持的格式时返回 `400` 和清晰错误信息
- [ ] `mvn test` 通过（含 PDF / Word / Markdown 解析与分块用例）

### 第 3 周

- [ ] `docker compose up -d` 能起一个带 `vector` 扩展的 PostgreSQL
- [ ] `AIKB_VECTOR_STORE=pgvector` 启动时自动建出 `ai_kb_vectors` 表与 HNSW 余弦索引
- [ ] `/api/rag/status` 能报告当前向量库实现与检索参数
- [ ] `/api/rag/ask` 返回结构化 `sources`，`location` 能定位到「文档 · 小节 · 第几段」
- [ ] 同一 `sessionId` 的两次提问能借助历史理解指代（多轮对话）
- [ ] 重复导入同一文档不会产生重复片段（按来源先删后写）
- [ ] `mvn test` 通过（含会话记忆、检索来源组装、引用校验、状态接口用例）

## 你需要理解的关键点

1. **分块**：为什么不能把整篇文档直接塞给模型？（`SimpleTextSplitter`）
2. **向量化**：`IngestionService` 把文本片段交给 `EmbeddingModel` 转成向量。
3. **检索**：`RagService` 用 `similaritySearch` 按语义找最相关的 `topK` 个片段。
4. **生成**：把检索结果拼成上下文，连同「只依据资料作答」的系统提示一起发给模型。
5. **解析**：真实文档格式各异，先把它们统一成纯文本（`TikaDocumentParser`），再谈分块。
6. **分块策略**：固定长度简单但会切断语义；按标题/段落切更贴合文档结构，但依赖文档质量。
7. **批量 embedding**：`IngestionService` 一次性把片段列表交给向量库，减少接口调用次数。
8. **向量库抽象**：上层只依赖 `VectorStore` 接口，内存与 pgvector 靠配置切换，换实现不动业务代码。
9. **引用溯源**：给片段编号并把来源写进提示词，模型才能在答案里标注 `[1]`；返回结构再把编号映射回原文位置。
10. **元数据过滤**：`source` 既可以用来限定检索范围，也可以用来「先删后写」实现重复导入去重。
11. **会话记忆窗口**：只保留最近 N 条消息，既让模型记住上下文，又不让历史把 token 撑爆。

## 常见问题

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
