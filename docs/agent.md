# Agent：Function Calling、多步规划与工具健壮性

> 覆盖 Agent 部分：把内部能力做成工具、让模型自己决定调用、用规则式规划器收敛工具权限，并给工具调用补上超时、重试与兜底。
> 更细的实现记录见 [agent-tool-calling.md](agent-tool-calling.md) 与 [agent-robustness.md](agent-robustness.md)。

## Agent 基础 —— Function Calling / Tool Use

助手只会「查资料 + 作答」，让它能「动手」：调用你自己的 Java 方法去查订单、查库存、算价格。

### 1. 什么是 Function Calling / Tool Use

模型本身不能访问你的数据库，但它可以在回答前说「请先帮我调用 `lookupOrder(orderId=A1001)`」。
Spring AI 负责把这次请求变成一次普通的 Java 方法调用，再把返回值回灌给模型，模型据此继续推理，
直到给出不再需要工具的最终答复。这个「模型请求 → 执行工具 → 回灌 → 再判断」的循环就叫**编排循环**。

我们要做的只有三件事：注册工具、把「何时用哪个工具」写进系统提示词、把过程记录下来。

### 2. 内置的四个工具

| 工具 | 类型 | 入参 | 返回 |
|---|---|---|---|
| `lookupOrder` | 业务查询 | 订单号 | 状态、商品、类别、是否定制、下单/收货时间、距收货天数、金额、是否已拆封 |
| `checkInventory` | 业务查询 | SKU | 单价、是否定制、各仓库库存、总库存 |
| `quotePrice` | 业务计算 | SKU + 数量 | 单价、小计、定制费、满减、总额 |
| `searchKnowledgeBase` | 知识检索 | 自然语言问题 | 带来源编号与定位的知识库原文片段 |

一个刻意的设计：**工具只返回事实，不返回结论**。`lookupOrder` 不会告诉你「能不能退」，
它只告诉你这单是不是定制商品、收货过了几天、有没有拆封；结论由模型结合 `searchKnowledgeBase`
查到的退货政策得出。这样工具保持通用、可复用，也把推理过程留在了可观测的地方。

### 3. 工具是怎么变成「模型能调的函数」的

- `agent/tool/*` 里的方法标上 `@Tool` 与 `@ToolParam`，`AgentToolRegistry` 用
  `MethodToolCallbackProvider` 把它们转成 Spring AI 的 `ToolCallback`；
- Spring AI 会把方法签名和注解描述生成成 **JSON Schema** 发给模型，模型据此决定调哪个工具、怎么填参数，
  `GET /api/agent/tools` 能把这份 Schema 原样打出来；
- 每个 `ToolCallback` 都再包一层 `RecordingToolCallback` 装饰器，记录工具名、参数、结果、耗时与状态，
  所以响应里的 `toolCalls` 能看到这一轮到底发生了什么；
- `ToolCallRecorder` 用 `ThreadLocal` 按线程收集一次提问内的全部调用，并做次数上限保护
  （`app.agent.max-tool-calls`，默认 6），避免模型陷入反复调用的循环；
- `app.agent.disabled-tools` 可以按场景裁掉工具（例如只想演示知识库问答），是权限边界的雏形。

### 4. 一次典型调用长什么样

```bash
curl -X POST http://localhost:8080/api/agent/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"订单 A1001 能不能退？"}'
```

预期行为（实际答复取决于模型）：

1. 模型调用 `lookupOrder(orderId=A1001)` → 拿到「定制商品、已签收、未拆封、收货 7 天」；
2. 模型调用 `searchKnowledgeBase(question=退货政策)` → 拿到「定制类商品不支持退货」；
3. 模型综合两者给出结论，并在答案里标注知识库来源编号。

返回结构里 `toolsUsed` 是去重后的工具名，`toolCalls` 是逐次明细，前端可以直接把「思考过程」展示出来。

## Agent 进阶 —— 多步规划与健壮性

把「一次提问调多个工具」跑通了，但两件事还没解决：**复杂任务没有显式的步骤规划**，**工具失败就整轮崩**。
补齐这两块：一层规则式规划器负责「先查什么、后查什么」，一层执行策略负责「超时怎么办、失败了怎么办」。

### 1. 规则式任务规划：把问题拆成有序步骤

`agent/plan/AgentPlanner` 不调用模型，只用关键词与订单号模式做判断，把问题拆成有序步骤：

| 识别到 | 计划里的步骤 | 对应工具 |
|---|---|---|
| 订单号 / 「订单」 | 查询订单事实（状态、商品、是否定制、收货天数、是否拆封） | `lookupOrder` |
| 「退」「政策」「规定」「年假」「报销」… | 检索公司政策 / 规定原文 | `searchKnowledgeBase` |
| 「库存」「有货」「还剩」 | 查询商品库存与单价 | `checkInventory` |
| 「多少钱」「报价」「买」 | 计算应付金额 | `quotePrice` |

步骤按依赖顺序排列：先拿到订单事实（含 SKU），再查政策，再查库存或算价，多于一步时补一个「汇总并给出结论」收尾。
它只做「粗粒度分步」，具体每一步怎么填参数、要不要跳过某一步，仍由模型在编排循环里决定——这正是 ReAct 的「思考骨架」。

规划结果有两种用法：

- **写进提示词**：把步骤渲染成一段说明注入系统提示词，让模型按顺序走、每一步基于上一步的结果；
- **收敛工具（最小权限）**：默认 `app.agent.planner.restrict-tools=true`，只把计划内的工具交给模型。
  识别不出任务时退回「全部工具」，不会把助手能力清零。

`POST /api/agent/plan` 可以把计划单独打出来，既方便演示，也方便用 curl 验证你的提问方式会不会被正确分步。

### 2. 工具执行策略：超时、重试与兜底

`agent/ToolExecutionPolicy` 给每次工具调用套上三层保护，配置都在 `app.agent.resilience.*`：

1. **超时**：`timeout-ms`（默认 2000ms）内没返回就取消，避免一个慢依赖拖垮整轮；
2. **重试**：失败后按 `initial-backoff-ms × backoff-multiplier^(n-1)` 指数退避重试，最多 `max-attempts`（默认 3）次；
3. **兜底**：重试耗尽后不再抛异常，而是返回一段结构化提示
   （`{"error":"...","fallback":true}`）当作工具结果回灌给模型，让模型就着已有信息作答、并如实说明哪项数据暂时拿不到。

对应的观测信息也补齐了：`toolCalls[]` 里每条记录多了 `attempts`（尝试了几次）与 `error`（最后一次错误），
状态除了 `OK` / `BLOCKED` 之外新增 `ERROR`（重试耗尽失败）与 `TIMEOUT`（重试耗尽超时）；
响应里的 `degraded` 汇总「本轮是否发生过失败兜底」。

为了能现场演示，策略还带了一个**故障注入**开关：把工具名填进 `fault-injection-tools`、并设定失败次数
`fault-injection-failures`，该工具的前 N 次调用就会必失败——正好用来演示「重试后成功」与「重试耗尽后兜底」两条路径。
生产环境留空即可。

### 3. 演示方式

```bash
# 0. 先导入示例文档，让 searchKnowledgeBase 有数据（可选，仅 ask 需要）
curl -X POST http://localhost:8080/api/rag/ingest

# 1. 看计划（不花钱）：复杂任务被拆成有序步骤
curl -X POST http://localhost:8080/api/agent/plan \
  -H "Content-Type: application/json" \
  -d '{"question":"订单 A1003 能退货吗？如果能退，再看看 SKU-3003 现在还剩多少库存。"}'

# 2. 走完整问答：响应里带上 plan 与 degraded
curl -X POST http://localhost:8080/api/agent/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"订单 A1003 能退货吗？如果能退，再看看 SKU-3003 现在还剩多少库存。"}'

# 3. 演示重试与兜底：注入故障后重启（见上一节）
```

## 快速体验

### 让助手自己调工具

先导入示例文档，Agent 才能查到退货政策：

```bash
curl -X POST http://localhost:8080/api/rag/ingest
```

看模型到底能看到哪些工具（不调用模型、不花钱）：

```bash
curl http://localhost:8080/api/agent/tools
```

然后问一个需要「查订单 + 查政策」的问题：

```bash
curl -X POST http://localhost:8080/api/agent/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"订单 A1001 能不能退？"}'
```

返回里除了 `answer`，还有 `toolsUsed` 和逐次调用的 `toolCalls`（工具名、参数、返回、耗时），
能直接看到模型先调了 `lookupOrder`、又调了 `searchKnowledgeBase`，再给出结论。

其他几个工具也能单独试：

```bash
curl -X POST http://localhost:8080/api/agent/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"SKU-2002 还有多少库存？"}'          # 触发 checkInventory

curl -X POST http://localhost:8080/api/agent/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"买 2 件 SKU-3003 一共多少钱？"}'      # 触发 quotePrice
```

### 多步规划与工具健壮性

先看一个问题会被拆成哪几步（`/api/agent/plan` 不调用模型、不花钱）：

```bash
curl -X POST http://localhost:8080/api/agent/plan \
  -H "Content-Type: application/json" \
  -d '{"question":"订单 A1003 能退货吗？如果能退，再看看 SKU-3003 现在还剩多少库存。"}'
```

返回里 `steps` 是有序步骤（最后一步是汇总作答），`tools` 是这一轮真正会暴露给模型的工具。
默认 `app.agent.planner.restrict-tools=true`，模型只能看到计划内的工具，从源头减少「调错工具」。

同一个问题走 `/api/agent/ask` 时，回答里会多两个字段：`plan`（本轮计划）与 `degraded`（是否发生过工具失败兜底）。

现场演示「工具偶发失败怎么办」（只改配置，不改代码）：

```bash
# 让 lookupOrder 前两次调用必失败，观察它退避重试到第三次成功
./build.sh spring-boot:run -Dspring-boot.run.arguments=\
"--app.agent.resilience.fault-injection-tools=lookupOrder --app.agent.resilience.fault-injection-failures=2"
```

把 `fault-injection-failures` 调成大于等于 `max-attempts`，就能看到重试耗尽后返回结构化兜底提示、
`toolCalls[].status` 变成 `ERROR`、响应里 `degraded=true`，而整轮请求依旧正常返回。

规划规则、健壮性设计与演示记录见 [`docs/agent-robustness.md`](agent-robustness.md)。
