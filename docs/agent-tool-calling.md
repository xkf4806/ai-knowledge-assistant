# Agent 基础（Function Calling / Tool Use）设计与演示

## 1. 目标

助手只会「查资料 + 作答」，让它能**动手**：自己决定调用哪个 Java 方法去拿数据，
再把工具结果和知识库内容组合成结论。验收标准是：

> 问「订单 A1001 能不能退」，助手能自动调用查订单工具、再查退货政策，最后给出条件判断结论。

一句话概括：**基于 Function Calling 实现工具调用，让助手可查询订单 / 库存等内部系统。**

## 2. 数据流

```
用户提问
  │
  ▼
AgentService.ask
  ├─ 取出会话历史
  ├─ recorder.begin()                       # 开始收集本轮工具调用
  ├─ ChatClient.prompt().tools(...).call()
  │      │
  │      ├─ 模型返回 tool_call: lookupOrder(orderId=A1001)
  │      ├─ Spring AI 执行 OrderTools.lookupOrder  → OrderView（事实）
  │      ├─ 工具结果回灌给模型
  │      ├─ 模型返回 tool_call: searchKnowledgeBase(question=退货政策)
  │      ├─ Spring AI 执行 KnowledgeBaseTools.searchKnowledgeBase → 带 [1] 编号的原文片段
  │      ├─ 工具结果回灌给模型
  │      └─ 模型输出最终答复（可能带 [1] 引用编号）
  ├─ recorder.end()                         # 收集本轮所有工具调用
  └─ 写入会话记忆，返回 answer + toolsUsed + toolCalls
```

「编排循环」由 Spring AI 的 `ChatClient` 内部完成，本项目只需要做好三件事：注册工具、写清工具用途、记录调用过程。

## 3. 工具清单

| 工具名 | 实现 | 入参 | 返回结构 | 设计要点 |
|---|---|---|---|---|
| `lookupOrder` | `agent/tool/OrderTools` | `orderId` | `OrderView` | 只给事实（是否定制、收货天数、是否拆封），不给「能不能退」的结论 |
| `checkInventory` | `agent/tool/InventoryTools` | `sku` | `InventoryView` | 返回各仓库库存与单价 |
| `quotePrice` | `agent/tool/PricingTools` | `sku`、`quantity` | `PriceQuote` | 多参数工具，演示模型如何填参数 |
| `searchKnowledgeBase` | `agent/tool/KnowledgeBaseTools` | `question` | `KnowledgeSearchResult` | 把混合检索 + 重排包装成工具，返回带来源编号的原文 |

数据来源：`src/main/resources/agent/orders.json` 与 `inventory.json`，由 `BusinessDataStore` 在启动时加载进内存。
真实项目里把 `BusinessDataStore` 换成 DAO 或内部接口客户端即可，工具方法签名与返回结构都不用动。

### 3.1 样例订单（决定演示效果）

| 订单号 | 商品 | 场景 | 结合退货政策的预期结论 |
|---|---|---|---|
| A1001 | 定制刻字保温杯（定制商品，未拆封，收货 7 天） | 定制类 | 不支持无理由退货（定制类商品除外条款） |
| A1002 | 无线蓝牙耳机（数码，已拆封，收货 17 天） | 超期 + 已拆封 | 不支持（超 7 天且已拆封影响二次销售） |
| A1003 | 纯棉四件套（家居，未拆封，收货 4 天） | 普通商品 | 支持无理由退货，往返运费由客户承担 |
| A1004 | 无线蓝牙耳机（运输中，未签收） | 在途 | 建议拒收 / 未签收不适用「已拆封」条款 |

> 退货政策原文见 `src/main/resources/sample-docs/refund-policy.md`；A1001 / A1002 专门做成「看起来能退、实际不行」，
> 用来检验模型是不是真的读了政策，而不是凭常识作答。

### 3.2 时间相关判断为什么要注入 Clock

`lookupOrder` 会返回「距收货已过天数」，它是判断「是否超过 7 天」的依据。为了让这个值可测试，
`OrderTools` 注入 `Clock`（`AgentConfig` 提供系统时钟），单元测试用固定时钟把「今天」钉死在 2026-10-11，
断言就与运行日期无关。

## 4. 关键实现

### 4.1 用注解而不是手写 JSON 定义工具

```java
@Tool(name = "lookupOrder",
        description = "根据订单号查询订单详情：状态、商品名称、SKU、类别、是否定制商品、"
                + "下单时间、收货时间、距收货已过天数、金额、是否已拆封。")
public OrderView lookupOrder(
        @ToolParam(description = "订单号，例如 A1001") String orderId) { ... }
```

`AgentToolRegistry` 用 `MethodToolCallbackProvider` 把这些方法转成 `ToolCallback`，
Spring AI 依据方法签名与注解描述生成 JSON Schema。`GET /api/agent/tools` 会把这份 Schema 打印出来，
可以直观看到「模型到底看到了什么」。

```bash
curl http://localhost:8080/api/agent/tools
```

### 4.2 工具调用可观测：装饰器 + ThreadLocal

模型调用了哪些工具，对调用方是不透明的（Spring AI 在内部循环里执行完，只把最终答复交给我们）。
为了让它可见，本项目加了两层：

- `RecordingToolCallback`：装饰器，包住 Spring AI 生成的 `ToolCallback`，记录工具名、入参、返回、耗时与状态（`OK`/`ERROR`/`BLOCKED`）；
- `ToolCallRecorder`：用 `ThreadLocal` 收集「一次提问」内的全部调用；阻塞式 `call()` 在同一线程执行工具，所以不需要改 Spring AI 的循环逻辑。

响应里的 `toolCalls` 就是这两个类产出的，它同时服务于三件事：前端展示「思考过程」、人工演示、自动化测试断言。

### 4.3 次数上限保护

`ToolCallRecorder.tryAcquire()` 在单轮调用超过 `app.agent.max-tool-calls`（默认 6）后拒绝继续执行，
`RecordingToolCallback` 直接返回一段 `{"error":"本轮工具调用次数已达上限..."}` 给模型，
避免问题过于宽泛时模型陷入「反复调用工具」的循环。被拦截的调用会以 `BLOCKED` 状态出现在 `toolCalls` 里。

### 4.4 按场景裁剪能力

`app.agent.disabled-tools` 可以屏蔽指定工具（例如只想演示知识库问答时屏蔽三个业务工具）。
被屏蔽的工具既不出现在 `/api/agent/tools`，也不会传给模型。这是「工具权限与安全边界」的雏形。

## 5. 演示方式

```bash
# 0. 启动（默认端口 8080），先导入示例文档，让 searchKnowledgeBase 有数据
curl -X POST http://localhost:8080/api/rag/ingest

# 1. 看看模型能用的工具（不调用模型、不花钱）
curl http://localhost:8080/api/agent/tools

# 2. 核心演示：需要「查订单 + 查政策」两个工具
curl -X POST http://localhost:8080/api/agent/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"订单 A1001 能不能退？"}'

# 3. 单工具演示
curl -X POST http://localhost:8080/api/agent/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"SKU-2002 还有多少库存？"}'

curl -X POST http://localhost:8080/api/agent/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"买 2 件 SKU-3003 一共多少钱？"}'

# 4. 多轮：第二次带 sessionId，用「那」指代上一轮的订单
curl -X POST http://localhost:8080/api/agent/ask \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"<上一步返回的 sessionId>","question":"那运费谁承担？"}'
```

每次响应关注三个字段：`answer`（结论）、`toolsUsed`（调了哪些工具）、`toolCalls`（逐次入参 / 返回 / 耗时）。

## 6. 验收对照

| 验收要求 | 落地位置 | 如何验证 |
|---|---|---|
| 2–3 个 Java 工具 | 3 个业务工具 + 1 个知识工具 | `GET /api/agent/tools` 返回 4 个工具及 Schema |
| 编排循环：判断 → 调用 → 回灌 → 生成 | Spring AI `ChatClient` 内部循环 | `POST /api/agent/ask` 的 `toolCalls` |
| 一轮内的多次工具调用 | `AgentService` 一次提问挂全部工具 | 问 A1001 时 `toolsUsed` 同时含查订单与查知识库 |
| 工具结果可观测 | `RecordingToolCallback` + `ToolCallRecorder` | 响应 `toolCalls` 含参数、返回、耗时、状态 |
| 不无限循环 | 单轮工具调用上限 | 单元测试断言第 N+1 次调用状态为 `BLOCKED` |
| 不调用模型也能回归 | 工具注册、业务工具、记录器测试 | `mvn test` 中 `com.example.aikb.agent.*` 全部通过 |

## 7. 已知边界与下一步

- 订单与库存是 JSON 模拟数据，不是真实内部系统；接口分层已经留好，替换成本很低。
- 工具调用的「上限保护」只是拒绝继续调用，还没有做重试、超时与降级——这是****的主题（多步规划与健壮性）。
- `disabled-tools` 是静态白名单，没有按用户身份区分权限；生产环境需要结合鉴权上下文决定可用工具。
- Agent 的工具调用过程目前只记在响应里，没有落库/落日志做离线分析；做可观测性时可以补 token 与耗时统计。
- 模型是否主动调工具受提示词和模型能力影响较大，换模型后需要重新核对（这也是为什么工具描述要写清楚）。

## 8. 相关文档

- 检索与评测：[`rag-evaluation.md`](rag-evaluation.md)
