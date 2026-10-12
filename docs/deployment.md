# 流式输出、前端、部署与可观测

> 覆盖「能给人看、能一条命令跑起来、能算成本」的部分：SSE 事件协议、零构建前端、容器化部署、
> token 用量与成本统计，以及联调时踩过的坑。完整的协议细节与实测记录见 [productization.md](productization.md)。

## 实现要点

的能力都在命令行里，把它们变成「能给人看、能一条命令跑起来、能算成本」的产品。

### 1. 三种模式共用一套 SSE 事件

| 事件 | 载荷 | 用途 |
|---|---|---|
| `meta` | `sessionId` / `mode` / `retrievalMode` / `sources` / `plan` | 第一帧：会话 ID 要回传给下一轮；RAG 顺带给出命中的来源，Agent 顺带给出本轮计划 |
| `delta` | `{text}` | 模型输出的一小段，前端逐段追加就是打字机效果 |
| `done` | `citations` / `toolsUsed` / `toolCalls` / `degraded` / `usage` / `estimatedCost` / `durationMs` | 收尾：引用编号、工具调用明细、本轮用量 |
| `error` | `{message}` | 失败原因（截断到 300 字符） |

一个必须理解的边界：**SSE 开始推送后 HTTP 状态码已经是 200**，之后的失败只能是 `error` 事件。
所以「推流之前」的参数错误照常返回 400，「推流之后」的模型 401 / 超时用事件下发，
前端在两种情况下都能显示可读的错误信息。

### 2. 前端

`src/main/resources/static/` 下三个文件，零构建，Spring Boot 直接托管：

| 文件 | 内容 |
|---|---|
| `index.html` | 顶栏（连接状态 / 向量库 / 知识库 / 用量 / 新会话）、对话区、输入区、用量面板、知识库弹窗 |
| `styles.css` | 中性底色 + 青绿主色；绿 / 琥珀 / 红只用于「成功 / 降级 / 失败」这类语义 |
| `app.js` | 三种模式、SSE 解析、轻量 Markdown、引用角标跳转、用量面板、文档上传 |

- 答案里的 `[1]` 会渲染成可点的角标，点一下展开对应来源并高亮；
- 智能助手模式把计划与每次工具调用（状态 / 耗时 / 重试次数 / 参数 / 返回）放进「调用详情」；
- 生成过程中「发送」旁边会出现「停止」，本质是 `AbortController` 断开请求，后端随之取消上游模型调用；
- 对话记录按模式存在 `localStorage`，**刷新浏览器后记录、引用角标与本轮用量都还在**，
  下一轮继续复用同一个 `sessionId`；「新会话」才会清空；
- 导入 / 上传有等待计时、180 秒上限与「取消」按钮，不会无限转圈；
- 图标来自 lucide 的 CDN；离线时按钮仍可用，只是没有图标。

### 3. 跨线程收集流式工具调用

阻塞式 `call()` 在同一个线程上顺序执行工具，`ThreadLocal` 就够用；流式不行——
工具跑在模型客户端的 Reactor 线程上。这里的做法是把本轮会话 ID 放进 `ToolContext`
（`recorder.beginScoped()` + `recorder.contextFor(scope)`），工具回调按 ID 找回同一个会话，
收尾时 `endScoped(scope)` 一次性取回并清理。找不到会话时「放行但不记录」，
宁可少一条观测数据，也不能把工具挡掉。

### 4. 可观测性：token 与成本

每次模型调用配对记录：`startCall(source, sessionId)` → `call.observe(response)`（流式喂每一片）
→ `finish(call)`；失败则 `fail(call, error)`，也会留一条（token 为 0、带错误原因）。

`GET /api/observability/usage` 返回：调用次数、失败次数、输入/输出/总 tokens、估算费用、
平均耗时、按入口（chat / rag / agent）与按模型的分解、最近 N 条明细。
统计放在进程内存里（重启清零，适合演示与自测）；要长期保存，把落点换成数据库即可。

### 5. 一条命令部署

```bash
cp .env.example .env          # 填 AI_API_KEY
docker compose up -d --build  # 构建应用镜像 + 起 pgvector + 起应用
```

- `Dockerfile` 两阶段构建：Maven 镜像里 `package`，JRE 镜像里运行，运行时 `-XX:MaxRAMPercentage=75`；
- `app` 服务等待 pgvector 健康检查通过再启动，并注入 `AIKB_VECTOR_STORE=pgvector` 与容器内的 JDBC 地址；
- 只想本地用 IDE 跑应用时，用 `docker compose up -d pgvector` 只起数据库。

### 6. 稳定性：别让一次网络抖动看起来像「卡死」

联调时踩到的两个坑，已经修掉并且加了配置项（细节见
[`docs/productization.md`](productization.md) 第 9 节）：

| 现象 | 原因 | 现在的行为 |
|---|---|---|
| 上传文档一直转圈 | Spring AI 默认重试 **10 次**、退避最长 3 分钟，且底层 HTTP 无读取超时 | 重试 2 次、退避 500ms/2s，连接 5s / 读取 60s 超时；前端 180s 上限 + 可取消，失败给可读原因 |
| 日志里的 `This connection has been closed` | 容器空闲后 PostgreSQL 先断开空闲连接，Hikari 取到死连接 | `max-lifetime` 5 分钟 + `keepalive` 2 分钟，不再等到死连接 |
| 重启后 `lexicalIndexSize` 变 0 | BM25 索引是内存态，只在导入时构建 | 启动时从 pgvector 重建关键词索引，重启后检索效果不变（不调用模型、不产生费用） |
| 流式问答只有第一帧、正文一直不来 | JDK HttpClient 默认 HTTP/2，在 WSL + docker bridge 的 NAT 链路上长连接静默卡死（非流式正常） | 模型 HTTP 客户端固定 HTTP/1.1；并给流式输出加「静默 90 秒就报错」的兜底，不再无限等 |
| 问答结束但界面显示「（无输出）」 | 网关流式接口偶发返回「消耗了 token 但 `content` 为空」的响应（非流式正常） | 检测到「整轮零字符」就改用非流式把同一轮重跑一遍（Agent 连工具调用一起重跑），把真实答案补推给前端 |

## 快速体验

### 流式对话、前端与用量成本

最简单的方式是直接打开界面（`http://localhost:8080`）：三种模式共用同一套 SSE 事件，
回答会一段段吐出来，引用角标可点，工具调用明细可展开，右侧面板实时显示 token 与估算费用。

只想在命令行看流式输出：

```bash
curl -N -X POST http://localhost:8080/api/rag/ask/stream \
  -H "Content-Type: application/json" \
  -d '{"question":"无理由退货的期限是几天？运费谁承担？"}'

# 事件顺序：meta（含来源）-> delta × N -> done（含引用与用量）
```

用量与成本（不调用模型、不花钱）：

```bash
curl http://localhost:8080/api/observability/usage      # 总览 + 按入口/模型分解 + 最近调用
curl -X DELETE http://localhost:8080/api/observability/usage   # 清零，方便从零演示
```

> 统计的是**对话模型**的 token；费用按 `app.observability.*` 的单价估算，
> 默认单价只是示例，务必按你所用供应商的价目表覆盖。embedding 的用量暂未计入。

一条命令拉起全部服务（应用 + pgvector，应用使用持久化向量库）：

```bash
cp .env.example .env    # 填上 AI_API_KEY
docker compose up -d --build
```

流式协议、错误边界（推流前 400/500 vs 推流后 `error` 事件）、
跨线程收集工具调用的做法与实测记录见 [`docs/productization.md`](productization.md)。
