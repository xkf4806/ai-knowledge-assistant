package com.example.aikb.rag.eval;

import com.example.aikb.rag.RetrievalMode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RAG 评测入口：可以只跑检索指标，也可以显式开启完整问答评测。
 *
 * <p>调用前先用 {@code POST /api/rag/ingest} 把样例文档索引到当前向量库。
 */
@RestController
@RequestMapping("/api/rag/eval")
public class RagEvaluationController {

    private final RagEvaluationService evaluationService;

    public RagEvaluationController(RagEvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    /** 查看评测问题、标准答案与期望来源，不调用 embedding / chat。 */
    @GetMapping("/dataset")
    public List<EvaluationCase> dataset() {
        return evaluationService.dataset();
    }

    /**
     * 运行评测。默认一次对比 vector / hybrid / hybrid-rerank 的检索指标；
     * 只有请求体显式传 {@code includeAnswers=true} 才会调用对话模型评测答案。
     */
    @PostMapping
    public List<EvaluationSummary> evaluate(@RequestBody EvaluationRequest request) {
        return request.resolvedModes().stream()
                .map(mode -> evaluationService.evaluate(mode, request.isIncludeAnswers()))
                .toList();
    }
}
