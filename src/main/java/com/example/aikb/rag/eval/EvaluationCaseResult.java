package com.example.aikb.rag.eval;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 单题评测明细，便于从汇总指标下钻到失败问题。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationCaseResult {

    private String id;
    private String question;
    private boolean hit;
    private int firstRelevantRank;
    private double precisionAtK;
    private List<String> retrieved;
    private String answer;
    private List<Integer> citations;
    private boolean answerCorrect;
    private boolean citationCorrect;
    private long latencyMs;
}
