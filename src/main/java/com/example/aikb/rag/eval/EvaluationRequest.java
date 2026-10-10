package com.example.aikb.rag.eval;

import com.example.aikb.rag.RetrievalMode;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Data
@NoArgsConstructor
public class EvaluationRequest {

    /** 要对比的检索模式；为空时一次对比 vector / hybrid / hybrid-rerank。 */
    private List<String> modes;

    /** 是否逐题调用对话模型检查答案准确率；默认关闭，避免批量评测产生意外费用。 */
    private boolean includeAnswers;

    public List<RetrievalMode> resolvedModes() {
        if (modes == null || modes.isEmpty()) {
            return List.of(RetrievalMode.VECTOR, RetrievalMode.HYBRID, RetrievalMode.HYBRID_RERANK);
        }
        Set<RetrievalMode> resolved = new LinkedHashSet<>();
        for (String mode : modes) {
            resolved.add(RetrievalMode.from(mode));
        }
        return List.copyOf(resolved);
    }
}
