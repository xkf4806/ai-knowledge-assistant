package com.example.aikb.rag;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 两阶段检索中的排序器（第 4 周新增）。
 *
 * <p>{@code hybrid} 使用 RRF（Reciprocal Rank Fusion）融合向量与关键词排名；
 * {@code hybrid-rerank} 再叠加向量分数、BM25 分数、问题词覆盖率与标题匹配度做确定性重排。
 * 这种重排不额外调用大模型，延迟与成本都可控，适合先建立可量化基线。
 */
@Service
public class RerankService {

    private static final double RRF_K = 60d;
    /**
     * 覆盖率/标题特征是 0~1 的比率，而 RRF 分数在 0.01 量级。
     * 乘这个系数后它们只负责打破近乎相同的排名，不会覆盖两路主信号。
     */
    private static final double FEATURE_SCALE = 0.001;

    private final RagProperties properties;

    public RerankService(RagProperties properties) {
        this.properties = properties;
    }

    /** 向量与 BM25 的候选，保存两路各自的排名与分数，供融合/重排使用。 */
    public record Candidate(Document document,
                            Integer vectorRank,
                            double vectorScore,
                            Integer lexicalRank,
                            double lexicalScore) {
    }

    public record RankedDocument(Document document, double score) {
    }

    /** 混合检索：使用 RRF 做无参数的稳健融合。 */
    public List<RankedDocument> fuseHybrid(List<Candidate> candidates, int limit) {
        return candidates.stream()
                .map(candidate -> new RankedDocument(
                        candidate.document(),
                        weightedRrfScore(candidate)))
                .sorted(rankedComparator())
                .limit(limit)
                .toList();
    }

    /** 混合召回后重排：加权 RRF 决定主顺序，关键词覆盖与标题匹配只做微调。 */
    public List<RankedDocument> rerank(String query, List<Candidate> candidates, int limit) {
        Set<String> queryTokens = new LinkedHashSet<>(Bm25LexicalIndex.tokenize(query));

        return candidates.stream()
                .map(candidate -> new RankedDocument(
                        candidate.document(),
                        rerankScore(candidate, queryTokens)))
                .sorted(rankedComparator())
                .limit(limit)
                .toList();
    }

    private double rerankScore(Candidate candidate, Set<String> queryTokens) {
        Set<String> documentTokens = new LinkedHashSet<>(
                Bm25LexicalIndex.tokenize(candidate.document().getText()));
        Set<String> headingTokens = new LinkedHashSet<>(
                Bm25LexicalIndex.tokenize(stringMetadata(candidate.document(), "heading")));

        double tokenCoverage = coverage(queryTokens, documentTokens);
        double headingCoverage = coverage(queryTokens, headingTokens);

        return weightedRrfScore(candidate)
                + FEATURE_SCALE * (properties.getRerankCoverageWeight() * tokenCoverage
                + properties.getRerankHeadingWeight() * headingCoverage);
    }

    private double weightedRrfScore(Candidate candidate) {
        return properties.getRerankVectorWeight() * reciprocalRank(candidate.vectorRank())
                + properties.getRerankLexicalWeight() * reciprocalRank(candidate.lexicalRank());
    }

    private static double reciprocalRank(Integer rank) {
        return rank == null || rank <= 0 ? 0 : 1d / (RRF_K + rank);
    }

    private static double coverage(Set<String> queryTokens, Set<String> documentTokens) {
        if (queryTokens.isEmpty()) {
            return 0;
        }
        long matched = queryTokens.stream().filter(documentTokens::contains).count();
        return (double) matched / queryTokens.size();
    }

    private static Comparator<RankedDocument> rankedComparator() {
        return Comparator.comparingDouble(RankedDocument::score).reversed()
                .thenComparing(ranked -> stringMetadata(ranked.document(), "source"))
                .thenComparingInt(ranked -> intMetadata(ranked.document(), "chunk"));
    }

    private static String stringMetadata(Document document, String key) {
        Object value = document.getMetadata().get(key);
        return value == null ? "" : String.valueOf(value).toLowerCase(Locale.ROOT);
    }

    private static int intMetadata(Document document, String key) {
        Object value = document.getMetadata().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return -1;
    }

}
