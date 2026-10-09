# 企业知识库智能助手（第 1 周：Spring AI + 最小 RAG 闭环）

这是 8 周求职路线图的第 1 周产物：一个能跑通的 **Spring AI + RAG** 后端骨架。
目标是先把「文档 → 向量 → 检索 → 大模型生成」这条链路跑起来，看清 RAG 的每一步。

## 技术栈

| 组件 | 版本 / 选型 |
|---|---|
| JDK | 17 |
| 构建 | Maven |
| 框架 | Spring Boot 3.5.15 |
| AI 框架 | Spring AI 1.1.8 |
| 模型接入 | OpenAI 兼容接口（默认网关内置 DeepSeek 对话模型） |
| 向量库 | 内存版 `SimpleVectorStore`（第 3 周换 pgvector） |

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
│   ├── RagController.java                 # /api/rag/ingest、/api/rag/ask
│   ├── RagService.java                    # 检索 + 拼上下文 + 生成
│   ├── IngestionService.java              # 读文档 -> 分块 -> 写向量库
│   ├── SimpleTextSplitter.java            # 固定长度 + 重叠的朴素分块器
│   └── RagProperties.java                 # app.rag.* 配置
└── web/PingController.java                # /api/ping 健康检查
src/main/resources
├── application.yml                        # 模型与 RAG 配置
├── prompts/system-prompt.txt              # 系统提示词（UTF-8，独立于源码）
└── sample-docs/                           # 两个示例知识库文档
```

## 快速开始

> **重要**：本项目需要 **JDK 17**。Maven 只认 `JAVA_HOME`，不看 PATH。
> 如果你的 `mvn -v` 显示的 `Java version` 不是 17（例如是 1.8），请先看下面的
> 「指定 JDK 17」一节，或用项目自带的 `.\build.ps1` 来构建。

### 1. 准备 API Key

默认配置使用一个同时提供**对话**与 **Embedding** 的 OpenAI 兼容网关，默认对话模型就是 DeepSeek。
去[硅基流动](https://siliconflow.cn)注册并创建 API Key（用一个 Key 即可跑通全链路）。

> 想换成 DeepSeek 官方 API？见下面「切换模型供应商」。

### 2. 配置环境变量（Windows PowerShell）

```powershell
$env:AI_API_KEY = "sk-你的真实key"
```

也可以直接用命令行参数或修改 `application.yml`（不建议把 Key 提交到 Git）。

### 3. 启动

```powershell
.\build.ps1 spring-boot:run
```

看到 `Started AiKnowledgeAssistantApplication` 即启动成功，默认端口 `8080`。

## 指定 JDK 17（重要）

Maven 通过 `JAVA_HOME` 决定用哪个 JDK。若你的系统 `JAVA_HOME` 指向 JDK 8，Maven 就会用 8 编译，
从而报出「需要class、interface或enum」「不支持 记录」之类的语法错误。

三种做法，任选其一：

**方式一（推荐，只影响当前终端）：**

```powershell
$env:JAVA_HOME = "D:\Program Files\Java\jdk-17"
mvn -v      # 确认 Java version 变成 17
```

**方式二（推荐，只影响本项目）：** 直接用项目自带脚本，自动切换 JDK 17：

```powershell
.\build.ps1 clean test
.\build.ps1 spring-boot:run
```

**方式三（永久生效，当前用户级，无需管理员）：**

```powershell
[Environment]::SetEnvironmentVariable("JAVA_HOME", "D:\Program Files\Java\jdk-17", "User")
```

设置后**需要关闭并重开终端**。注意：这会让本机所有新开的终端都用 JDK 17；
如果你还有依赖 JDK 8 的旧项目，改用方式一或方式二更稳妥。

### 4. 验证

先测健康检查（不需要 Key）：

```powershell
Invoke-RestMethod http://localhost:8080/api/ping
```

再测基础对话连通性：

```powershell
Invoke-RestMethod -Method Post http://localhost:8080/api/chat `
  -ContentType "application/json" `
  -Body '{"message":"用一句话介绍你自己"}'
```

导入示例知识库（这一步会调用 Embedding 接口）：

```powershell
Invoke-RestMethod -Method Post http://localhost:8080/api/rag/ingest
```

基于知识库提问：

```powershell
Invoke-RestMethod -Method Post http://localhost:8080/api/rag/ask `
  -ContentType "application/json" `
  -Body '{"question":"无理由退货的期限是几天？运费谁承担？"}'
```

返回里 `answer` 是答案，`sources` 是命中的文档片段来源。

## 接口一览

| 方法 | 路径 | 说明 | 需要 Key |
|---|---|---|---|
| GET | `/api/ping` | 健康检查 | 否 |
| POST | `/api/chat` | 基础对话（无知识库） | 是 |
| POST | `/api/rag/ingest` | 导入示例文档到向量库 | 是 |
| POST | `/api/rag/ask` | 基于知识库提问 | 是 |

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

## 第 1 周验收清单

- [ ] `mvn test` 通过
- [ ] `/api/ping` 返回 `UP`
- [ ] `/api/chat` 能正常对话
- [ ] `/api/rag/ingest` 能把 `sample-docs` 写入向量库
- [ ] `/api/rag/ask` 能基于文档作答，并返回来源；问文档外的问题会承认「无法回答」

## 你需要理解的关键点

1. **分块**：为什么不能把整篇文档直接塞给模型？（`SimpleTextSplitter`）
2. **向量化**：`IngestionService` 把文本片段交给 `EmbeddingModel` 转成向量。
3. **检索**：`RagService` 用 `similaritySearch` 按语义找最相关的 `topK` 个片段。
4. **生成**：把检索结果拼成上下文，连同「只依据资料作答」的系统提示一起发给模型。

## 常见问题

- **`mvn test` 报「需要class、interface或enum」或「不支持 记录/文本块」**：编译用的 JDK 版本不对。
  本项目需要 JDK 17，请执行 `mvn -v` 确认 `Java version` 为 17；若不是，把 `JAVA_HOME` 指向 JDK 17 并重开终端。
  项目已内置版本校验，JDK 低于 17 会直接给出明确提示。
- **启动报 API Key 错误**：确认 `$env:AI_API_KEY` 已设置，且重启了应用。
- **调用报 401/403**：Key 无效或额度不足。
- **调用报 404 或模型不存在**：`AI_CHAT_MODEL` / `AI_EMBEDDING_MODEL` 与所用网关的模型名不匹配。
- **重启后检索不到内容**：内存向量库重启即清空，需要重新调用 `/api/rag/ingest`（第 3 周换成 pgvector 后即可持久化）。
