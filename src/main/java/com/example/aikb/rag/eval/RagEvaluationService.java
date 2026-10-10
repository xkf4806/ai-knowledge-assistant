package com.example.aikb.rag.eval;

import com.example.aikb.chat.ConversationMemoryService;
import com.example.aikb.rag.AskRequest;
import com.example.aikb.rag.RagAnswer;
import com.example.aikb.rag.RagService;
import com.example.aikb.rag.RetrievalMode;
import com.example.aikb.rag.RetrievalService;
import com.example.aikb.rag.RetrievedChunk;
import com.example.aikb.rag.SourceRef;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * RAG 评测服务（第 4 周学习重点）。
 *
 * <p>默认只评测检索层，统计 Hit Rate、MRR 与 Precision@K，不调用对话模型。
 * 显式开启 {@code includeAnswers} 后，才会逐题走完整 RAG 链路并检查关键事实与引用来源。
 */
@Slf4j
@Service
public class RagEvaluationService {

    private static final String DEFAULT_DATASET = "evaluation/rag-eval.json";

    private final RetrievalService retrievalService;
    private final RagService ragService;
    private final ConversationMemoryService memory;
    private final List<EvaluationCase> dataset;

    public RagEvaluationService(RetrievalService retrievalService,
                                RagService ragService,
                                ConversationMemoryService memory,
                                ObjectMapper objectMapper) {
        this.retrievalService = retrievalService;
        this.ragService = ragService;
        this.memory = memory;
        this.dataset = loadDataset(objectMapper);
    }

    public List<EvaluationCase> dataset() {
        return dataset;
    }

    public EvaluationSummary evaluate(RetrievalMode mode, boolean includeAnswers) {
        List<EvaluationCaseResult> caseResults = new ArrayList<>(dataset.size());
        int hitCount = 0;
        int answerCorrectCount = 0;
        int citationCorrectCount = 0;
        double reciprocalRankSum = 0;
        double precisionSum = 0;
        long latencySum = 0;

        for (EvaluationCase evaluationCase : dataset) {
            long startedAt = System.nanoTime();
            List<RetrievedChunk> hits = retrievalService.retrieve(
                    evaluationCase.question(), null, mode);
            int firstRelevantRank = firstRelevantRank(evaluationCase, hits);
            if (firstRelevantRank > 0) {
                hitCount++;
                reciprocalRankSum += 1d / firstRelevantRank;
            }
            double precision = precisionAtK(evaluationCase, hits);
            precisionSum += precision;

            String answer = null;
            List<Integer> citations = List.of();
            boolean answerCorrect = false;
            boolean citationCorrect = false;
            if (includeAnswers) {
                EvaluationAnswer evaluated = evaluateAnswer(evaluationCase, mode);
                answer = evaluated.answer();
                citations = evaluated.citations();
                answerCorrect = evaluated.answerCorrect();
                citationCorrect = evaluated.citationCorrect();
                if (answerCorrect) {
                    answerCorrectCount++;
                }
                if (citationCorrect) {
                    citationCorrectCount++;
                }
            }

            long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
            latencySum += latencyMs;
            caseResults.add(new EvaluationCaseResult(
                    evaluationCase.id(),
                    evaluationCase.question(),
                    firstRelevantRank > 0,
                    firstRelevantRank,
                    round(precision),
                    hits.stream()
                            .map(RetrievedChunk::sourceRef)
                            .map(RagEvaluationService::diagnosticLocation)
                            .toList(),
                    answer,
                    citations,
                    answerCorrect,
                    citationCorrect,
                    latencyMs));
        }

        int total = dataset.size();
        double hitRate = ratio(hitCount, total);
        double mrr = total == 0 ? 0 : reciprocalRankSum / total;
        double precisionAtK = total == 0 ? 0 : precisionSum / total;
        double answerAccuracy = includeAnswers ? ratio(answerCorrectCount, total) : 0;
        double citationAccuracy = includeAnswers ? ratio(citationCorrectCount, total) : 0;
        double averageLatency = total == 0 ? 0 : (double) latencySum / total;

        EvaluationSummary summary = new EvaluationSummary(
                mode.wireName(),
                total,
                hitCount,
                round(hitRate),
                round(mrr),
                round(precisionAtK),
                includeAnswers,
                round(answerAccuracy),
                round(citationAccuracy),
                round(averageLatency),
                List.copyOf(caseResults));
        log.info("RAG 评测完成：mode={}, hitRate={}, mrr={}, precision@K={}",
                summary.getMode(), summary.getHitRate(), summary.getMrr(), summary.getPrecisionAtK());
        return summary;
    }

    private EvaluationAnswer evaluateAnswer(EvaluationCase evaluationCase, RetrievalMode mode) {
        String sessionId = "eval-" + UUID.randomUUID();
        try {
            RagAnswer answer = ragService.ask(new AskRequest(
                    evaluationCase.question(), sessionId, null, mode.wireName()));
            Set<String> citedSources = citedSources(answer);
            boolean expectedSourceCited = citedSources.contains(evaluationCase.expectedSource());
            boolean keywordsCovered = evaluationCase.expectedKeywords() == null
                    || evaluationCase.expectedKeywords().stream()
                    .allMatch(keyword -> normalize(answer.getAnswer()).contains(normalize(keyword)));
            boolean answerCorrect = expectedSourceCited && keywordsCovered;
            boolean citationCorrect = !citedSources.isEmpty()
                    && citedSources.stream().allMatch(evaluationCase.expectedSource()::equals);
            return new EvaluationAnswer(
                    answer.getAnswer(),
                    answer.getCitations(),
                    answerCorrect,
                    citationCorrect);
        } finally {
            memory.clear(sessionId);
        }
    }

    private static Set<String> citedSources(RagAnswer answer) {
        if (answer == null || answer.getCitations() == null || answer.getSources() == null) {
            return Set.of();
        }
        Set<String> sources = new LinkedHashSet<>();
        for (Integer citation : answer.getCitations()) {
            if (citation == null || citation < 1 || citation > answer.getSources().size()) {
                continue;
            }
            SourceRef source = answer.getSources().get(citation - 1);
            if (source != null && source.getSource() != null) {
                sources.add(source.getSource());
            }
        }
        return sources;
    }

    private static String diagnosticLocation(SourceRef source) {
        if (source == null) {
            return "";
        }
        return source.getLocation()
                + " | score=" + source.getScore()
                + ", vector=" + source.getVectorScore()
                + ", lexical=" + source.getLexicalScore();
    }

    private static int firstRelevantRank(EvaluationCase evaluationCase, List<RetrievedChunk> hits) {
        for (RetrievedChunk hit : hits) {
            if (matches(evaluationCase, hit.sourceRef())) {
                return hit.index();
            }
        }
        return 0;
    }

    private static double precisionAtK(EvaluationCase evaluationCase, List<RetrievedChunk> hits) {
        if (hits.isEmpty()) {
            return 0;
        }
        long relevant = hits.stream()
                .map(RetrievedChunk::sourceRef)
                .filter(source -> matches(evaluationCase, source))
                .count();
        return (double) relevant / hits.size();
    }

    private static boolean matches(EvaluationCase evaluationCase, SourceRef source) {
        if (source == null || !evaluationCase.expectedSource().equals(source.getSource())) {
            return false;
        }
        if (evaluationCase.expectedHeading() == null || evaluationCase.expectedHeading().isBlank()) {
            return true;
        }
        return normalize(source.getHeading()).contains(normalize(evaluationCase.expectedHeading()));
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{P}\\p{S}]", "");
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private static double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    private static List<EvaluationCase> loadDataset(ObjectMapper objectMapper) {
        try (InputStream input = new ClassPathResource(DEFAULT_DATASET).getInputStream()) {
            List<EvaluationCase> cases = objectMapper.readValue(
                    input, new TypeReference<List<EvaluationCase>>() {
                    });
            if (cases.isEmpty()) {
                throw new IllegalStateException("评测集不能为空: " + DEFAULT_DATASET);
            }
            return List.copyOf(cases);
        } catch (IOException e) {
            throw new UncheckedIOException("加载 RAG 评测集失败: " + DEFAULT_DATASET, e);
        }
    }

    private record EvaluationAnswer(String answer,
                                    List<Integer> citations,
                                    boolean answerCorrect,
                                    boolean citationCorrect) {
    }
}
