# 第 4 周：RAG 评测与优化记录

## 1. 目标

第 4 周不再靠主观感觉判断 RAG 效果，而是固定一份评测集，对比：

- `vector`：只走向量检索，作为优化前基线；
- `hybrid`：向量 + BM25 关键词召回，用 RRF 融合排名；
- `hybrid-rerank`：混合召回后，再按向量分、BM25 分、问题词覆盖率和标题匹配度重排。

代码内置评测集位于 `src/main/resources/evaluation/rag-eval.json`，共 26 条问题，覆盖员工手册、退款政策、IT 支持三份文档。

## 2. 指标口径

| 指标 | 口径 |
|---|---|
| Hit Rate | Top-K 中至少命中期望文档与标题的问题占比 |
| MRR | 第一个正确片段的排名倒数均值，越接近 1 越好 |
| Precision@K | Top-K 片段里命中期望来源的比例均值 |
| Answer Accuracy | 可选；答案覆盖标准关键事实，且引用了期望文档 |
| Citation Accuracy | 可选；答案引用的来源全部正确 |
| Average Latency | 每题平均耗时；开启答案评测时包含对话模型耗时 |

检索指标默认不会调用对话模型，但仍会调用 embedding 来做向量召回。

## 3. 运行方式

先启动应用并配置 `AI_API_KEY`，然后执行：

```bash
./scripts/evaluate-rag.sh
```

Windows PowerShell：

```powershell
.\scripts\evaluate-rag.ps1
```

如需评测完整答案链路，会逐题调用对话模型，请确认成本后再开启：

```bash
AIKB_EVAL_ANSWERS=true ./scripts/evaluate-rag.sh
```

也可以直接调用接口：

```bash
curl -X POST http://localhost:8080/api/rag/eval \
  -H "Content-Type: application/json" \
  -d '{"modes":["vector","hybrid","hybrid-rerank"],"includeAnswers":false}'
```

## 4. 优化前后对比

评测时间：2026-10-10。数据集 26 条，先用 `includeAnswers=false` 跑检索指标，再用 `includeAnswers=true` 对 `vector` 与 `hybrid-rerank` 跑完整问答评测。
机器可读的汇总结果见 [`week4-evaluation-summary.json`](week4-evaluation-summary.json)。

### 4.1 检索指标

| 模式 | Hit Rate | MRR | Precision@K | Avg Retrieval Latency (ms) |
|---|---:|---:|---:|---:|
| vector | 1.0000 | 1.0000 | 0.2500 | 123.38 |
| hybrid | 1.0000 | 1.0000 | 0.2500 | 125.85 |
| hybrid-rerank | 1.0000 | 1.0000 | 0.2500 | 126.35 |

### 4.2 完整问答评测

| 模式 | Hit Rate | MRR | Answer Accuracy | Citation Accuracy | Avg Full Latency (ms) |
|---|---:|---:|---:|---:|---:|
| vector | 1.0000 | 1.0000 | 0.7692 | 1.0000 | 2065.81 |
| hybrid-rerank | 1.0000 | 1.0000 | 0.8462 | 1.0000 | 2217.96 |

### 4.3 结论

- 检索层在这份 26 条样例集上已经饱和，三种模式的 Hit Rate 与 MRR 都是 1.0，主要瓶颈已经转到答案组织而不是召回。
- 混合检索 + 重排把答案准确率从 **76.92% 提升到 84.62%**（+7.70pp），引用准确率保持 **100%**。
- 完整问答链路平均延迟从 **2065.81ms 增加到 2217.96ms**，约 +152ms；纯检索层只增加约 3ms，说明主要延迟来自对话模型生成。
- 重排修正的典型样本是 `hr-annual-leave-increase` 与 `refund-custom-products`：关键词精确命中“定制”等词项后，模型更容易引用正确片段并覆盖标准事实。

## 5. 复盘

- 当前样例集偏小，向量检索的语义优势已经足够覆盖大部分问题；下一步要加入更多相似章节、编号类问题和干扰文档，才能让 Hit Rate / MRR 拉开差距。
- `Precision@K` 稳定在 0.25 是评测口径造成的：每题只标注 1 个正确片段，`topK=4` 的理论上限就是 0.25。
- 剩余答案错误主要是关键事实覆盖不完整或模型表述省略数字，需要继续检查答案模板是否过短、是否需要把关键数字要求写进提示词。
- 延迟增加 152ms 换来 7.7pp 答案准确率，对求职演示可以接受；真实生产要用更完整的离线评测集确认收益是否稳定。

## 6. 已知边界

- BM25 索引保存在进程内存中，重启后需要重新执行 `/api/rag/ingest` 或重新上传文档。
- `pgvector` 只负责持久化向量；关键词索引目前与向量索引使用同一份摄取入口，但不是数据库持久化实现。
- `includeAnswers=true` 会产生对话模型费用，默认关闭。
- 当前评测集规模适合求职项目演示，不等同于生产级全量离线评测。
