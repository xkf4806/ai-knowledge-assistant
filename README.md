# 企业知识库智能助手（第 1–2 周：最小 RAG 闭环 + 文档解析与分块）

这是 8 周求职路线图的第 1–2 周产物：一个能跑通的 **Spring AI + RAG** 后端骨架。
第 1 周先把「文档 → 向量 → 检索 → 大模型生成」这条链路跑起来，看清 RAG 的每一步；
第 2 周补上**真实文档解析**与**可配置分块策略**，让系统不再只能吃手写的示例文本。

## 技术栈

| 组件 | 版本 / 选型 |
|---|---|
| JDK | 17 |
| 构建 | Maven |
| 框架 | Spring Boot 3.5.15 |
| AI 框架 | Spring AI 1.1.8 |
| 模型接入 | OpenAI 兼容接口（默认网关内置 DeepSeek 对话模型） |
| 向量库 | 内存版 `SimpleVectorStore`（第 3 周换 pgvector） |
| 文档解析 | Apache Tika 3.2.3（PDF / Word / Markdown / HTML / txt 等） |
| 分块策略 | `fixed` / `heading` / `paragraph`，参数可配置 |

## 目录结构

```
src/main/java/com/example/aikb
├── AiKnowledgeAssistantApplication.java   # 启动类
├── chat/                                  # 基础对话（不接知识库）
│   ├── ChatController.java
│   ├── ChatRequest.java
│   └── ChatResponse.java
├── config/RagConfig.java                  # 声明内存向量库 Bean
├── rag/                                   # RAG 核心
│   ├── RagController.java                 # /api/rag/ingest、/upload、/preview、/ask
│   ├── RagService.java                    # 检索 + 拼上下文 + 生成
│   ├── IngestionService.java              # 解析 -> 分块 -> 批量写向量库；preview 只分块
│   ├── IngestResponse.java                # 含 documents / chunks / strategy / sources
│   ├── PreviewResponse.java               # 分块预览响应（含多策略对比）
│   ├── ChunkPreview.java / StrategyPreview.java
│   ├── SimpleTextSplitter.java            # 固定长度 + 重叠的底层切分（第 1 周）
│   ├── RagProperties.java                 # app.rag.* 配置
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

## 接口一览

| 方法 | 路径 | 说明 | 需要 Key |
|---|---|---|---|
| GET | `/api/ping` | 健康检查 | 否 |
| POST | `/api/chat` | 基础对话（无知识库） | 是 |
| POST | `/api/rag/ingest` | 导入示例文档到向量库，可传 `strategy` / `chunkSize` / `overlap` | 是 |
| POST | `/api/rag/upload` | 上传 PDF / Word / Markdown 等文档，解析 + 分块 + 索引 | 是 |
| POST | `/api/rag/preview` | 解析 + 分块预览，可一次对比多种策略与参数 | 否 |
| POST | `/api/rag/ask` | 基于知识库提问 | 是 |

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

## 切换模型供应商

都用环境变量覆盖即可，无需改代码：

| 变量 | 说明 | 默认值 |
|---|---|---|
| `AI_BASE_URL` | 兼容接口根地址（**不要带 `/v1`**） | `https://api.siliconflow.cn` |
| `AI_API_KEY` | API Key | `sk-change-me` |
| `AI_CHAT_MODEL` | 对话模型 | `deepseek-ai/DeepSeek-V3` |
| `AI_EMBEDDING_MODEL` | Embedding 模型 | `BAAI/bge-m3` |

**改用 DeepSeek 官方 API 做对话时注意**：DeepSeek 官方**不提供 Embedding 接口**。
由于 Spring AI 的 OpenAI 客户端只接受一个 `base-url`，你需要在代码里单独定义一个指向 Embedding 服务的
`EmbeddingModel` Bean（第 3 周接入 pgvector 时会一并处理）。这也是一个很好的理解「模型供应商解耦」的练习点。

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

## 你需要理解的关键点

1. **分块**：为什么不能把整篇文档直接塞给模型？（`SimpleTextSplitter`）
2. **向量化**：`IngestionService` 把文本片段交给 `EmbeddingModel` 转成向量。
3. **检索**：`RagService` 用 `similaritySearch` 按语义找最相关的 `topK` 个片段。
4. **生成**：把检索结果拼成上下文，连同「只依据资料作答」的系统提示一起发给模型。
5. **解析**：真实文档格式各异，先把它们统一成纯文本（`TikaDocumentParser`），再谈分块。
6. **分块策略**：固定长度简单但会切断语义；按标题/段落切更贴合文档结构，但依赖文档质量。
7. **批量 embedding**：`IngestionService` 一次性把片段列表交给向量库，减少接口调用次数。

## 常见问题

- **启动报 API Key 错误**：确认 `AI_API_KEY` 已设置（如 `export AI_API_KEY=sk-...`），且重启了应用。
- **调用报 401/403**：Key 无效或额度不足。
- **调用报 404 或模型不存在**：`AI_CHAT_MODEL` / `AI_EMBEDDING_MODEL` 与所用网关的模型名不匹配。
- **重启后检索不到内容**：内存向量库重启即清空，需要重新调用 `/api/rag/ingest`（第 3 周换成 pgvector 后即可持久化）。
- **上传报「不支持的文档格式」**：检查扩展名是否在支持列表中；扫描件 PDF 只有图片、抽不出文字，需要 OCR（暂未接入）。
- **上传报「解析文档失败」**：文档可能损坏或加密，先用阅读器确认能正常打开。
- **分块预览结果为空**：文档抽出的文本可能为空，或该格式扫描件没有文字层。
