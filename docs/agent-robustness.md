# Agent 进阶（多步规划与健壮性）设计与演示

## 1. 目标

让助手「能调一个工具」：模型自己决定调 `lookupOrder` 还是 `searchKnowledgeBase`。
但两件事还没解决：

1. **复杂任务没有显式的步骤规划**——「先查订单、再查政策、最后查库存」这套顺序全靠模型临场发挥，不可解释、也不好验收；
2. **工具一失败整轮就崩**——内网依赖会超时、会偶发报错，处理是直接抛异常。

补齐这两块，验收标准：

> 一个需要多次工具调用的复杂任务能完成；工具失败时有合理兜底。

一句话概括：**实现多步任务规划与工具编排，支持失败重试、结果校验与调用权限控制。**

## 2. 数据流

```
用户提问
  │
  ▼
AgentService.ask
  ├─ AgentPlanner.plan(question)              # 规则式分步，不调用模型
  │     └─ 产出 Plan：有序步骤 + 计划内工具 + 规划理由
  ├─ toolRegistry.callbacksFor(plan.tools())  # 只把计划内的工具交给模型（最小权限）
  ├─ 把 plan 渲染成一段说明，拼进 system prompt
  ├─ recorder.begin()
  ├─ ChatClient.prompt().system(...).tools(...).call()
  │      │
  │      ├─ 模型请求 lookupOrder(A1003)
  │      │     └─ RecordingToolCallback
  │      │           └─ ToolExecutionPolicy：超时 + 重试 + 兜底
  │      ├─ 模型请求 searchKnowledgeBase(退货政策)
  │      ├─ 模型请求 checkInventory(SKU-3003)
  │      └─ 模型输出最终答复
  ├─ recorder.end()
  └─ 返回 answer + toolsUsed + toolCalls + plan + degraded
```

和相比，多出来的两个层次很清晰：**规划在模型之前**（喂步骤、收工具），**健壮性在工具之内**（超时、重试、兜底）。

## 3. 规则式任务规划

### 3.1 为什么要自己规划，而不是让模型规划

让模型先输出一份计划再执行，也是一种做法，但有三个代价：多一次模型调用（更慢更贵）、计划不稳定（同一问题两次结果不同）、
难以回归测试。这里选择**规则式规划**：用关键词与订单号模式判断意图，毫秒级、零成本、可被单元测试钉死。
它只做「粗粒度分步」这一件事，具体参数怎么填仍由模型决定——规划器给「思考」的骨架，模型跑「行动 / 观察」。

### 3.2 识别规则

`agent/plan/AgentPlanner` 的规则集中在几张关键词表上（见源码常量 `ORDER_HINTS` / `POLICY_HINTS` / `INVENTORY_HINTS` / `PRICE_HINTS` / `KNOWLEDGE_HINTS`）：

| 命中 | 计划步骤 | 对应工具 |
|---|---|---|
| 订单号 `A\d{4}`、`订单`、`单号`、`物流`、`签收` | 查询订单事实 | `lookupOrder` |
| `退`、`政策`、`规定`、`制度`、`流程`、`条款`、`手册`、`年假`、`报销`、`VPN`… | 检索公司政策 / 规定原文 | `searchKnowledgeBase` |
| `库存`、`有货`、`现货`、`备货`、`还剩`、`仓库` | 查询商品库存与单价 | `checkInventory` |
| `多少钱`、`价格`、`报价`、`总价`、`单价`、`买` | 计算应付金额 | `quotePrice` |

命中后按**依赖顺序**排列：先拿订单事实（里面才有 SKU），再查政策，再查库存或算价；步骤多于一步时补一个「汇总并给出结论」收尾。
识别不出任何意图（例如「你好呀」）时返回**空计划**，上层退回「把全部工具交给模型」。

### 3.3 计划结构

```json
{
  "question": "订单 A1003 能退货吗？如果能退，再看看 SKU-3003 现在还剩多少库存。",
  "planned": true,
  "steps": [
    {"order": 1, "action": "查询订单事实",     "tool": "lookupOrder",          "detail": "获取订单状态、商品与 SKU、是否定制、收货天数、是否拆封"},
    {"order": 2, "action": "检索公司政策/规定", "tool": "searchKnowledgeBase",  "detail": "检索退货、退款、制度等原文，作为结论依据"},
    {"order": 3, "action": "查询商品库存与单价", "tool": "checkInventory",      "detail": "获取商品单价、是否定制与各仓库可用库存"},
    {"order": 4, "action": "汇总并给出结论",    "detail": "综合以上工具结果作答，涉及政策时标注知识库引用编号"}
  ],
  "tools": ["lookupOrder", "searchKnowledgeBase", "checkInventory"],
  "rationale": "识别到 订单查询、政策检索、库存查询，按依赖顺序拆成 4 步，只向模型暴露计划内的 3 个工具。"
}
```

### 3.4 计划怎么被用起来

- **写进提示词**：`AgentPlanner.promptHint` 把步骤渲染成一段中文说明，拼在系统提示词后面，要求模型按顺序执行、每步基于上一步结果；
- **收敛工具（最小权限）**：`AgentToolRegistry.callbacksFor(tools)` 只保留计划内的 `ToolCallback`，
  模型看不到本轮用不到的工具，从源头减少「调错工具」；若计划里的工具恰好都被 `disabled-tools` 屏蔽，则退回全部已注册工具，避免能力被清零；
- **可观测**：`/api/agent/ask` 的响应带上 `plan`，`/api/agent/plan` 可以单独预览计划。

两个开关：`app.agent.planner.enabled`（关掉后不规划，工具选择完全交给模型）、
`app.agent.planner.restrict-tools`（关掉后仍生成计划，但保留全部工具）。

## 4. 工具执行健壮性

`agent/ToolExecutionPolicy` 给每次工具调用套上三层保护，配置前缀 `app.agent.resilience`。

### 4.1 超时

工具放到一个守护线程池上执行，`timeout-ms`（默认 2000ms）内没返回就 `cancel(true)` 取消，避免一个慢依赖拖垮整轮。
`timeout-ms <= 0` 表示关闭超时，直接在调用线程执行。

### 4.2 重试（指数退避）

失败后按 `initial-backoff-ms × backoff-multiplier^(n-1)` 等待再重试，最多 `max-attempts`（默认 3）次。
默认参数下：第 1 次重试等 50ms，第 2 次等 100ms。退避等待抽成了 `Sleeper`，单元测试注入空实现即可跳过等待。

### 4.3 失败兜底

重试耗尽后**不再抛异常**，而是返回一段结构化提示当作工具结果回灌给模型：

```json
{"error":"工具 lookupOrder 执行失败（已尝试 3 次：IllegalStateException: 模拟工具故障）。请基于已获取的信息作答；若该数据是结论所必需，请如实告知用户暂时无法获取，不要编造。","fallback":true}
```

模型据此可以就着已有信息作答，或如实说明「订单数据暂时查不到」，而整轮请求依旧 200 返回。
系统提示词里也明确写了：看到 `"fallback":true` 或 `error` 字段时不许用编造数据补位。

### 4.4 故障注入（为了能现场演示）

内网偶发失败很难复现，所以策略带了一个开关：

- `app.agent.resilience.fault-injection-tools`：要注入故障的工具名列表；
- `app.agent.resilience.fault-injection-failures`：这些工具的前 N 次调用被强制失败。

配 `lookupOrder` + `2`，就能看到它失败两次、第三次成功；把 `2` 调成 `>= max-attempts`，就能看到重试耗尽后的兜底路径。
生产环境留空即可，测试里也用它验证两条路径。

### 4.5 观测字段

`ToolCallRecord` 每条多出两个字段：

| 字段 | 含义 |
|---|---|
| `attempts` | 这一次调用共尝试了几次；>1 表示重试过（`retried` 派生字段） |
| `error` | 重试耗尽后的最后一次错误；成功或拦截时为 null |

`Status` 在 `OK` / `BLOCKED` 之外新增 `ERROR`（重试耗尽失败）与 `TIMEOUT`（重试耗尽超时）。
`AgentAnswer.degraded` 汇总「本轮是否发生过失败兜底」，方便前端提示「部分数据暂不可用」。

> 注意区分：`BLOCKED` 是**次数上限**保护（压根没执行），`ERROR` / `TIMEOUT` 是执行过但最终失败并兜底。

## 5. 演示方式

```bash
# 0. 启动（默认端口 8080）

# 1. 看计划（不调用模型、不花钱）
curl -X POST http://localhost:8080/api/agent/plan \
  -H "Content-Type: application/json" \
  -d '{"question":"订单 A1003 能退货吗？如果能退，再看看 SKU-3003 现在还剩多少库存。"}'

# 2. 单工具 / 双工具问题也会被正确分步
curl -X POST http://localhost:8080/api/agent/plan -H "Content-Type: application/json" \
  -d '{"question":"订单 A1001 能不能退？"}'          # 2 步：lookupOrder + searchKnowledgeBase
curl -X POST http://localhost:8080/api/agent/plan -H "Content-Type: application/json" \
  -d '{"question":"买 2 件 SKU-3003 一共多少钱？"}'   # 1 步：quotePrice

# 3. 完整问答（需要先 /api/rag/ingest 导入示例文档）
curl -X POST http://localhost:8080/api/agent/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"订单 A1003 能退货吗？如果能退，再看看 SKU-3003 现在还剩多少库存。"}'

# 4. 演示重试：lookupOrder 前两次失败，第三次成功
./build.sh spring-boot:run -Dspring-boot.run.arguments=\
"--app.agent.resilience.fault-injection-tools=lookupOrder --app.agent.resilience.fault-injection-failures=2"

# 5. 演示兜底：把失败次数调到 >= max-attempts，观察 degraded 与 ERROR 状态
./build.sh spring-boot:run -Dspring-boot.run.arguments=\
"--app.agent.resilience.fault-injection-tools=lookupOrder --app.agent.resilience.fault-injection-failures=3"
```

每次 `/api/agent/ask` 关注四个字段：`answer`（结论）、`plan`（怎么查的）、`toolCalls`（逐次入参 / 返回 / 耗时 / 尝试次数 / 状态）、`degraded`（是否降级）。

## 6. 验收对照

| 验收要求 | 落地位置 | 如何验证 |
|---|---|---|
| 多步任务能完成 | `AgentPlanner` 分步 + 提示词引导 + `callbacksFor` 收敛工具 | 问 A1003 退货+库存，`plan.steps` 有 4 步、`toolsUsed` 含三个工具 |
| 简单的 planner / 决策逻辑 | `agent/plan/AgentPlanner`（规则式，不调用模型） | `/api/agent/plan` 返回步骤；`AgentPlannerTest` 8 个用例 |
| 工具选择错误的兜底 | 只暴露计划内工具 | `/api/agent/tools` 与响应 `plan.tools` 对照 |
| 工具超时 | `ToolExecutionPolicy` 超时 + 取消 | `markTimedOutCallsAsTimeout` / `timesOutSlowCalls` |
| 工具重试 | 指数退避 + `attempts` 记录 | `retriesTransientFailuresAndRecordsAttempts` |
| 工具失败兜底 | 结构化 `fallback` 提示回灌模型 | `returnsFallbackInsteadOfThrowingWhenRetriesAreExhausted` |
| 现场可演示 | 故障注入配置 | 见第 5 节演示命令 |
| 不调用模型也能回归 | 规划器、策略、注册表测试 | `mvn test` 中 `com.example.aikb.agent.*` 全部通过 |

## 7. 已知边界与下一步

- 规划器是**关键词规则**，覆盖的是演示语料；换领域要重写关键词表，或改成「小模型先规划」（代价是多一次调用、结果不稳定）。
- 重试是**同参数重试**，还没有做「换个工具/换种问法再试」的降级策略。
- 超时靠取消线程实现，对「不可中断」的阻塞调用只能做到不再等待、线程仍可能占用。
- 工具调用的失败与重试目前只随响应返回，没有落库/落日志做离线分析——做可观测性时可以补 token、耗时与失败率统计。
- 权限只管「这一轮暴露哪些工具」，没有按用户身份区分权限；生产环境要结合鉴权上下文决定可用工具。
- 工具调用过程仍以「单轮 ThreadLocal」收集，没有跨轮的任务状态机；真正的长任务需要持久化的执行图。

## 8. 相关文档

- 工具调用设计：[`agent-tool-calling.md`](agent-tool-calling.md)
- 检索与评测：[`rag-evaluation.md`](rag-evaluation.md)
