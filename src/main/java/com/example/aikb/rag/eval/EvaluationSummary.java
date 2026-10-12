package com.example.aikb.rag.eval;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 一种检索模式在一份评测集上的汇总结果。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationSummary {

    private String mode;
    private int total;
    private int hits;
    private double hitRate;
    private double mrr;
    private double precisionAtK;
    private boolean answersEvaluated;
    private double answerAccuracy;
    private double citationAccuracy;
    private double averageLatencyMs;
    private List<EvaluationCaseResult> cases;
}
