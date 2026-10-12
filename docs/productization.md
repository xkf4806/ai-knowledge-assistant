# 产品化（流式输出 + 前端 + 一条命令部署 + 用量成本）设计与演示

## 1. 目标

做出来的东西「能用」，但还停留在 curl 和 JSON 里：答案要等完整生成才返回、没有界面、
启动要背一串环境变量、模型花了多少 token 全靠猜。把这些补齐，验收标准：

> 一条命令拉起全部服务，能现场演示流式对话。

一句话概括：**实现 SSE 流式响应与容器化部署，加入对话追踪与 token 成本统计。**

## 2. 数据流

```
浏览器（static/index.html + app.js）
  │  POST /api/{chat|rag|agent}/…/stream
  ▼
Controller  ── 参数校验（不通过直接 400，此时还没开始推流）
  │
  ▼  SseStreams.toEmitter(Flux<StreamEvent>)
Service（ChatService / RagService / AgentService）
  │  ① meta：会话 ID / 模式 / 来源 / 计划       ← 第一帧就把「这一轮要干什么」告诉前端
  │  ② delta × N：ChatClient.stream().chatResponse() 的每一片
  │       └─ 顺手喂给 ModelCallObserver（累计 token 与耗时）
  │  ③ done：引用编号 / 工具调用明细 / 用量 / 估算费用
  ▼
SseEmitter ──► text/event-stream ──► 前端逐帧渲染（打字机 + 引用角标 + 工具轨迹）
```

此前的可观测性只覆盖「工具调用」，这里往前又包了一层：**模型调用也被记下来了**，
于是「这一轮花了多少 token、多少钱、多久」都能在界面上看到。

## 3. 流式输出

### 3.1 事件协议

三个入口共用同一套事件，前端因此只需要一份解析逻辑（`app.js` 里的 `readStream` / `parseEvent`）：

| 事件 | 载荷 | 说明 |
|---|---|---|
| `meta` | `sessionId / mode / retrievalMode / sources / plan` | 第一帧；`sessionId` 要回传给下一轮 |
| `delta` | `{text}` | 模型输出的一小段，逐段追加就是打字机效果 |
| `done` | `citations / toolsUsed / toolCalls / plan / degraded / usage / estimatedCost / durationMs` | 收尾；答案已输出完，补上结构化信息 |
| `error` | `{message}` | 失败原因（截断到 300 字符） |

### 3.2 为什么用 SseEmitter 而不是直接返回 Flux

Spring MVC 支持返回 `Flux`，但 `SseEmitter` 把「事件名、JSON 序列化、超时、客户端断开后的清理」
都握在自己手里，行为最确定。代价是要自己订阅 Reactor 流：

```java
Disposable subscription = events.subscribe(
        event -> send(emitter, event),                        // 正常事件
        error -> { send(emitter, StreamEvent.error(error)); complete(emitter); },
        () -> complete(emitter));
emitter.onCompletion(subscription::dispose);                  // 客户端断开就取消上游
emitter.onTimeout(() -> { subscription.dispose(); complete(emitter); });
```

### 3.3 一个必须讲清楚的边界：错误发生在推流之前还是之后

SSE 一旦开始推送，HTTP 状态码就已经是 200 了，**之后再失败不可能改成 500**。所以这里按时间点分两类：

- **推流之前**（参数为空、RAG 的检索阶段就失败）：照常抛异常，由 `ApiExceptionHandler` 返回 400 / 500 的 JSON；
- **推流之后**（模型网关 401、超时）：用 `error` 事件下发，前端在对话里显示一条红色提示。

实测（故意不给 Key）：

```
POST /api/chat/stream  {"message":"你好"}

event:meta
data:{"sessionId":"8a3b304c-…","mode":"chat"}

event:error
data:{"message":"Unauthorized: 401 Unauthorized from POST https://api.siliconflow.cn/v1/chat/completions"}
```

同样地，`RagController` 的检索是**阻塞在推流之前**完成的（要先拼好参考资料），
所以 embedding 报错会走 HTTP 错误分支：

```
POST /api/rag/ask/stream  {"question":"退货期限几天？"}
500 {"error":"NonTransientAiException: HTTP 401 - {\"message\":\"Token is invalid.\"}"}
```

这个取舍是有意的：**「还没开始回答就失败」用 HTTP 语义更诚实，「回答到一半失败」只能走事件。**

### 3.4 流式下的工具调用怎么还被记录到

第 5、6 周用 `ThreadLocal` 收集本轮工具调用，前提是阻塞式 `call()` 在同一个线程上顺序执行工具。
流式不行：工具在模型客户端的 Reactor 线程上执行，拿不到请求线程的 `ThreadLocal`。

做法是把「本轮会话 ID」通过 Spring AI 的 `ToolContext` 传下去：

```java
ToolCallRecorder.Scope scope = recorder.beginScoped();            // 登记一个可跨线程找回的会话
chatClient.prompt()… .toolContext(recorder.contextFor(scope))      // 随请求带下去
                     .stream().chatResponse();
…
List<ToolCallRecord> calls = recorder.endScoped(scope);           // 收尾时按 ID 取回并清理
```

工具回调从 `ToolContext` 里读到 ID，找回同一个会话——**一轮一账，跨线程也不丢**。
万一上下文没传到（找不到会话），策略是「放行但不记录」：宁可少一条观测数据，也不能把工具挡掉。

## 4. 前端

单页、零构建，直接放在 `src/main/resources/static/`，由 Spring Boot 一起提供
（打开 `http://localhost:8080/` 就是它）：

| 文件 | 作用 |
|---|---|
| `index.html` | 结构：顶栏、对话区、输入区、右侧用量面板、知识库弹窗 |
| `styles.css` | 视觉：中性底色 + 青绿主色，状态色（绿 / 琥珀 / 红）只用在语义上 |
| `app.js` | 逻辑：三种模式、SSE 解析、轻量 Markdown、引用跳转、用量面板 |

几个刻意的设计：

- **三种模式一个界面**：知识库问答 / 智能助手 / 基础对话走同一套事件协议，切换只是换 `endpoint` 和请求体；
- **引用角标可点**：答案里的 `[1]` 渲染成按钮，点击展开「来源片段」并高亮对应那一条；
- **过程可见**：智能助手模式把计划步骤与每次工具调用（状态 / 耗时 / 重试次数 / 参数 / 返回）放在「调用详情」里；
- **能停下**：发送按钮旁是停止按钮，本质是 `AbortController` 断开 fetch，后端靠 `onCompletion` 取消上游；
- **用量面板**：每次回答结束自动刷新 `/api/observability/usage`，显示调用次数、估算费用、tokens、平均耗时与最近调用。

> 图标用 lucide 的 CDN 版本。离线时按钮依然可用，只是没有图标——没有把整包图标塞进仓库。

## 5. 一条命令部署

`Dockerfile` 两阶段构建：`maven:3.9-eclipse-temurin-17` 里打包，`eclipse-temurin:17-jre` 里运行，
运行时用 `-XX:MaxRAMPercentage=75` 按容器内存给堆设上限。

`docker-compose.yml` 现在是两个服务：

```bash
# 一条命令：构建应用镜像 + 起 pgvector + 起应用（应用自动连容器网络里的数据库）
AI_API_KEY=sk-xxx docker compose up -d --build
open http://localhost:8080

# 本地用 IDE 跑应用时，只起数据库就够了
docker compose up -d pgvector
```

`app` 服务通过 `depends_on: pgvector: condition: service_healthy` 等数据库健康检查通过再启动，
并把 `AIKB_VECTOR_STORE=pgvector`、`AIKB_DB_URL=jdbc:postgresql://pgvector:5432/aikb` 注入进去——
**一键起的是「能持久化的完整形态」，不是内存版**。

## 6. 用量与成本统计

`observability/ModelCallObserver` 提供一次调用配对的 API：

```java
ModelCallObserver.Call call = observer.startCall("rag", sessionId);
call.observe(response);        // 阻塞式喂一次；流式把每一片都喂进来
ModelCallRecord record = observer.finish(call);   // 失败则 observer.fail(call, error)
```

它会做三件事：

1. 从 `ChatResponse.metadata` 里取模型名与 token 用量（流式分片用「有值的一方」合并）；
2. 按配置的单价估算费用：`prompt/1e6*promptPrice + completion/1e6*completionPrice`；
3. 累计到进程级汇总（总览 + 按入口 + 按模型 + 最近 N 条明细），失败调用也记一条（token 为 0，带错误原因）。

接口：

```bash
curl http://localhost:8080/api/observability/usage             # 总览 + 分解 + 最近调用
curl -X DELETE http://localhost:8080/api/observability/usage   # 清零，方便从零演示
```

实测返回（无 Key 时的一次失败调用）：

```json
{"enabled":true,"currency":"CNY","calls":1,"failedCalls":1,"totalTokens":0,"estimatedCost":0.0,
 "bySource":{"agent":{"calls":1,"failedCalls":1,"totalDurationMs":442}},
 "recentCalls":[{"id":"call-1","source":"agent","model":"unknown","success":false,
                 "error":"Unauthorized: 401 …","durationMs":442}]}
```

### 这一块的边界（建议主动讲清）

- **单价是示例值**：`app.observability.prompt-price-per-1m` / `completion-price-per-1m` 默认
  `2.0 / 8.0`（元 / 百万 tokens），必须按你所用供应商的价目表覆盖，否则「费用」只表示量级；
- **只统计对话模型**：embedding 的 token 没有单独计入（要计就得在 `IngestionService` / `RetrievalService` 里也埋点）；
- **累计在内存**：重启清零，适合演示与自测；要长期保存，把 `ModelCallObserver.record` 换成落库即可；
- **多实例不共享**：进程内统计，多副本部署需要换成集中式指标（Micrometer + Prometheus 是自然的下一步）。

## 7. 演示脚本

```bash
# 0. 配置 Key（不配也能演示：界面会显示可读的 401 错误，用量面板照样统计失败调用）
export AI_API_KEY=sk-xxx

# 1. 一条命令拉起全部服务
docker compose up -d --build

# 2. 打开界面（顶栏会显示后端连接状态）
open http://localhost:8080

# 3. 知识库模式：先「知识库 → 导入并索引」，再问
#    「无理由退货的期限是几天？运费谁承担？」——看打字机效果、[1] 角标可点、来源片段可展开

# 4. 智能助手模式：问「订单 A1003 能退货吗？再看看 SKU-3003 的库存。」
#    展开「调用详情」：4 步计划 + 3 次工具调用（含状态、耗时、重试次数）

# 5. 点顶栏「用量」：调用次数、估算费用、总 tokens、按入口 / 按模型分解、最近调用明细

# 6. 想直接看原始 SSE（不经过浏览器）：
curl -N -X POST http://localhost:8080/api/rag/ask/stream \
  -H "Content-Type: application/json" \
  -d '{"question":"无理由退货的期限是几天？"}'
```

## 8. 留个底：承认的不足

- 前端是原生 JS 单文件，没有工程化（打包、类型、单元测试），够演示不够长期维护；
- `delta` 每来一片就整段重渲染一次 Markdown，长答案会有可感知的开销，正确做法是增量渲染；
- 没有断线重连 / 续传（`Last-Event-ID`），网络抖动会导致这一轮中断；
- 用量只统计对话模型、只存在内存里，多副本不共享；
- Docker 镜像里没加健康检查与 JVM 指标端点（`/actuator/prometheus` 是下一步）。

## 9. 修复记录（2026-10-12）

### 9.1 刷新浏览器丢掉对话记录

**原因**：对话记录只存在内存里的 DOM 节点上，刷新即清零；`sessionId` 也没有落盘，
所以后端记着上下文、界面却什么都看不见。

**修复**（`static/app.js`）：把「按模式保存的消息记录」变成唯一数据源，
每次渲染都从记录重建 DOM：

- 记录写进 `localStorage`（`aikb.chat.v1`）：角色、正文、命中的来源、收尾事件（用量/引用/工具调用）；
- 同时落盘三个模式各自的 `sessionId` 与当前模式，刷新后回到同一个标签页，下一轮继续用同一个会话；
- 写入时机是「发送时」与「本轮结束时」，不在每个 `delta` 上写；上限保留最近 30 条、单条正文截断到 20000 字符，
  隐私模式或配额满时静默跳过，不影响对话；
- 「新会话」会同时清掉界面记录与本地存储，并调用后端的 `DELETE .../sessions/{id}`。

**验证**（无头 Chrome + 假后端）：提问 → 刷新 → 界面上仍有 2 条消息、2 个引用角标、来源片段可展开；
刷新后再提问，请求体里带的是刷新前的 `sessionId`；切到智能助手再刷新，工具调用明细同样还原，切回知识库记录也还在。

### 9.2 上传文档「卡住」，后端报 embedding 超时

现场日志两条关键信息：

```
HikariPool-1 - Failed to validate connection ... (This connection has been closed.). Possibly consider using a shorter maxLifetime value.
SpringAiRetryAutoConfiguration : Retry error. Retry count: 1, Exception: I/O error on POST request for "https://api.siliconflow.cn/v1/embeddings": Connection timed out
```

**原因**（两条独立的问题叠在一起）：

1. Spring AI 的默认重试是 **10 次**、退避 `2s / 10s / 50s / 180s / 180s…`，
   而底层 HTTP 客户端**没有读取超时**。一次网络抖动就会让上传请求「看起来卡死」好几分钟；
2. 容器长时间空闲后，空闲的 PostgreSQL 连接会先被对端断掉，Hikari 拿到死连接时打一条 WARN
   （它自己会换新连接，所以这条 WARN 是噪音而非故障，但很容易被误读成根因）。

**修复**：

| 位置 | 改动 |
|---|---|
| `spring.ai.retry` | `max-attempts: 2`、退避 `500ms / 2s`、4xx 不重试 → 失败尽快暴露 |
| `config/HttpClientConfig` | 给模型 RestClient 加 `connect 5s` / `read 60s` 超时（流式走 WebClient，不受影响，长回答不会被掐） |
| `spring.datasource.hikari` | `max-lifetime 5m` + `keepalive 2m` + 校验/连接超时 → 不再拿到已断开的空闲连接 |
| `IngestionService` | 大文档按 32 片一批写入并打进度日志；失败时抛出可读信息（含根因与「旧片段已被替换，需要重新索引」） |
| `static/app.js` | 上传/导入有等待计时、180 秒上限与「取消」按钮，超时/取消都明确提示「后端可能仍在处理」 |

**验证**：真实上传一份 Markdown（3 个片段，约 1 秒）→ 用它的内容提问，答案带 `[1]` 引用并定位到该文件；
重启 pgvector 容器后不做任何操作，再问一次依旧正常（Hikari 自动换掉了死连接）。

### 9.3 顺带修掉：重启后关键词召回静默降级

**原因**：BM25 索引是内存态、只在导入时构建。之前用 pgvector 持久化后，重启虽然向量数据还在，
`lexicalIndexSize` 却变成 0 —— 混合检索悄悄退化成纯向量召回，这一点只能从状态接口看出来。

**修复**（`rag/LexicalIndexRebuilder`）：应用就绪后，若使用 pgvector，就把库里的片段读回来
按来源重建关键词索引；只读数据库、不调用模型接口，失败也只降级为「本轮关键词召回为空」并打 WARN。

**验证**：重启后启动日志出现 `已从向量库重建关键词索引：4 个来源、125 个片段`，
`/api/rag/status` 的 `lexicalIndexSize` 从 0 变成 125（含用户自己上传的 114 片段 PDF），
并成功用该 PDF 的内容提问、拿到带引用的答案。

### 9.4 流式对话只有 `meta`、正文一直不来

**现象**：`/api/chat/stream`、`/api/rag/ask/stream`、`/api/agent/ask/stream` 都只推了第一帧 `meta`，
之后几十秒什么都没有，界面上只看到一个闪烁的光标；非流式接口 `/api/chat`、`/api/rag/ask` 却一切正常。

**排查过程**（值得记下来，因为「排除了错误的嫌疑人」比结论本身更花时间）：

| 验证 | 结果 |
|---|---|
| 同一条 curl 直接打硅基流动的 `stream:true`（含 `stream_options.include_usage`） | 正常，15KB SSE |
| 同一个 jar 跑在 Windows 主机上，打 `/api/chat/stream` | 正常，1 秒内 `meta → delta × N → done` |
| 容器里打 `/api/chat/stream` | 只有 `meta`，挂死 |
| 容器镜像里的 HTTP 客户端 | 只有 `reactor-core`，没有 reactor-netty / HttpComponents → 走 **JDK HttpClient** |
| 同一网络用 curl 打网关 | 偶发 `HTTP/2 stream 0 was not closed cleanly: PROTOCOL_ERROR` |

**根因**：JDK HttpClient 默认使用 **HTTP/2**。在这套 WSL + docker bridge 的 NAT 链路上，
HTTP/2 长连接会静默卡死——TCP/TLS 都正常，短请求（嵌入、非流式对话）能跑完，
但流式响应（长连接、持续读）拿不到任何帧，于是「连上了，但永远没有正文」。

**修复**：

1. `config/HttpClientConfig` 里两条通路共用同一个 JDK HttpClient，并**固定 `HTTP/1.1`**
   （可用 `AIKB_AI_USE_HTTP2=true` 切回 HTTP/2）；流式那条同时显式绑定 `JdkClientHttpConnector`，
   不再依赖 Spring Boot 自动挑选连接器；
2. 新增 `stream/StreamTimeouts.guardIdle`：`delta` 之间（含第一个字）静默超过 `app.stream.idle-timeout-ms`
   （默认 90 秒）就抛 `ModelStreamTimeoutException`，由上层转成一条可读的 `error` 事件；
   这样即使换到别的网络再出问题，用户看到的也是「为什么没有回答」，而不是无限光标；
3. 注意同步通路仍然保留 60 秒读取超时，而流式通路**不设总超时**，避免长回答被掐断。

**验证**：容器内 `protocol=HTTP/1.1` 之后，`/api/chat/stream` 与 `/api/rag/ask/stream`（13 个 delta + 引用）恢复正常；
用一个「知识库里没有的」问题（华为 nova 8 售价）打 `/api/agent/ask/stream`，
4.9 秒内完成：模型调用 `checkInventory(sku="华为nova 8")` → 工具返回「未查询到」→ 如实回答，
`toolCalls` 里能看到这次调用（状态 OK、1ms）。

### 9.5 网关流式返回「空内容」时界面显示「无输出」

**现象**：HTTP/1.1 修好之后，某些问题仍然只有光标、最后界面显示「（无输出）」。

**排查**：绕过应用，直接用同一份 `tools` + 同一个问题请求网关的流式接口，抓到了网关自己的原始响应：

```
data: {...,"choices":[{"index":0,"delta":{"content":""},"finish_reason":null}],"usage":{... "completion_tokens":0 ...}}
data: {...,"choices":[{"index":0,"delta":{"content":null},"finish_reason":"stop"}],"usage":{... "completion_tokens":116 ...}}
data: [DONE]
```

**结论**：网关这次**消耗了 116 个 token 却一个字的正文都没发**（既没有 `tool_calls`，也没有 `content`），
直接 `finish_reason=stop` 结束。同一个问题走**非流式**接口完全正常（会正常调用 `checkInventory` 并给出答案）。
也就是说：这是网关流式接口的偶发缺陷，应用不能假设「流结束 = 拿到了答案」。

**修复**（三个流式入口统一）：

- 收尾时检查本轮流出了多少字：**一个字符都没有**就改用**非流式把同一轮完整重跑一遍**
  （Agent 模式连规划与工具调用一起重跑：抽出 `runOnce` 供 `ask` 与兜底共用），
  把拿到的答案作为一段 `delta` 补推给前端，界面因此显示真实答案而不是「无输出」；
- 这次空流**单独记一条失败**（`EmptyModelResponseException`，带 token 消耗），
  兜底那次正常记一条，用量面板里能看清「一次空响应 + 一次成功的重跑」；
- `ModelCallObserver` 增加「一次调用只记一条」的保护，避免「空流失败 + 兜底」这类路径重复计数；
- 兜底本身也拿不到内容时，抛 `EmptyModelResponseException`，由错误事件把原因讲清楚；
- 前端把「（无输出）」换成「模型这次没有返回内容，可以再问一次。」

**验证**（同一个问题连问三次，正好抓到一次偶发空流）：

| 次数 | 结果 |
|---|---|
| 第 1、2 次 | 正常流式：31 / 39 个 delta，`checkInventory` 返回「未查询到」，答案正常 |
| 第 3 次 | 日志先出现 `模型流式输出为空，改用非流式重跑一轮（含工具调用）`，随后 `Agent 非流式兜底完成…调用工具 1 次 [checkInventory]`；前端收到 **1 个 delta**（整段答案）+ `done`（含工具调用明细），界面正常显示答案 |

顺带回归：`/api/rag/ask/stream`（12 个 delta + 引用 `[1]`）与 `/api/chat/stream`（31 个 delta）都正常。
