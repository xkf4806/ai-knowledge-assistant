package com.example.aikb.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索服务：向量召回、BM25 混合召回与候选重排（第 4 周升级）。
 *
 * <p>{@link RetrievalMode#VECTOR} 保留第 3 周行为，作为评测基线；
 * 默认的 {@link RetrievalMode#HYBRID_RERANK} 先各取候选，再按两路排名、关键词覆盖率和标题匹配度重排。
 * 这样既保留向量检索的语义能力，也能修复订单号、专有名词、数字条件等精确词项的召回。
 */
@Slf4j
@Service
public class RetrievalService {

    /** 返回给前端的片段摘要长度，够看清依据即可，避免响应体过大。 */
    private static final int SNIPPET_LENGTH = 120;

    private final VectorStore vectorStore;
    private final RagProperties properties;
    private final LexicalIndex lexicalIndex;
    private final RerankService rerankService;

    public RetrievalService(VectorStore vectorStore,
                            RagProperties properties,
                            LexicalIndex lexicalIndex,
                            RerankService rerankService) {
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.lexicalIndex = lexicalIndex;
        this.rerankService = rerankService;
    }

    /** 按 application.yml 的默认模式检索。 */
    public List<RetrievedChunk> retrieve(String question, String sourceFilter) {
        return retrieve(question, sourceFilter, properties.getRetrievalMode());
    }

    /**
     * 按指定模式检索与问题最相关的片段。
     *
     * @param question     用户问题
     * @param sourceFilter 可选：只在该文件名范围内检索（元数据过滤）
     * @param mode         检索模式；评测可显式传 vector / hybrid / hybrid-rerank
     */
    public List<RetrievedChunk> retrieve(String question, String sourceFilter, RetrievalMode mode) {
        RetrievalMode resolvedMode = mode == null ? properties.getRetrievalMode() : mode;
        if (resolvedMode == RetrievalMode.VECTOR) {
            List<Document> hits = vectorSearch(question, sourceFilter, properties.getTopK());
            return toRetrievedChunks(hits, resolvedMode, Map.of());
        }

        int candidateK = Math.max(properties.getTopK(), properties.getCandidateK());
        List<Document> vectorHits = vectorSearch(question, sourceFilter, candidateK);
        List<LexicalHit> lexicalHits = lexicalIndex.search(question, sourceFilter, candidateK);
        if (vectorHits.isEmpty() && lexicalHits.isEmpty()) {
            log.info("混合检索未命中任何片段{}",
                    sourceFilter == null || sourceFilter.isBlank() ? "" : "（限定文档：" + sourceFilter + "）");
            return List.of();
        }

        Map<String, RerankService.Candidate> candidates = mergeCandidates(vectorHits, lexicalHits);
        List<RerankService.RankedDocument> ranked = resolvedMode == RetrievalMode.HYBRID
                ? rerankService.fuseHybrid(new ArrayList<>(candidates.values()), properties.getTopK())
                : rerankService.rerank(question, new ArrayList<>(candidates.values()), properties.getTopK());

        List<RetrievedChunk> chunks = new ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            RerankService.RankedDocument rankedDocument = ranked.get(i);
            RerankService.Candidate candidate = candidates.get(keyOf(rankedDocument.document()));
            int index = i + 1;
            double finalScore = round(rankedDocument.score());
            chunks.add(new RetrievedChunk(
                    index,
                    rankedDocument.document().getText(),
                    finalScore,
                    toSourceRef(index, rankedDocument.document(), finalScore, resolvedMode, candidate)));
        }
        log.info("混合检索命中 {} 个片段，模式={}，候选数={}{}",
                chunks.size(),
                resolvedMode.wireName(),
                candidates.size(),
                sourceFilter == null || sourceFilter.isBlank() ? "" : "（限定文档：" + sourceFilter + "）");
        return chunks;
    }

    private List<Document> vectorSearch(String question, String sourceFilter, int topK) {
        SearchRequest.Builder request = SearchRequest.builder()
                .query(question)
                .topK(topK);
        if (sourceFilter != null && !sourceFilter.isBlank()) {
            request.filterExpression("source == '" + escape(sourceFilter.trim()) + "'");
        }

        List<Document> hits = vectorStore.similaritySearch(request.build());
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        return hits;
    }

    private Map<String, RerankService.Candidate> mergeCandidates(List<Document> vectorHits,
                                                                  List<LexicalHit> lexicalHits) {
        Map<String, RerankService.Candidate> candidates = new LinkedHashMap<>();
        for (int i = 0; i < vectorHits.size(); i++) {
            Document document = vectorHits.get(i);
            String key = keyOf(document);
            RerankService.Candidate existing = candidates.get(key);
            candidates.put(key, new RerankService.Candidate(
                    document,
                    i + 1,
                    scoreOf(document),
                    existing == null ? null : existing.lexicalRank(),
                    existing == null ? 0 : existing.lexicalScore()));
        }
        for (int i = 0; i < lexicalHits.size(); i++) {
            LexicalHit hit = lexicalHits.get(i);
            Document document = hit.document();
            String key = keyOf(document);
            RerankService.Candidate existing = candidates.get(key);
            candidates.put(key, new RerankService.Candidate(
                    document,
                    existing == null ? null : existing.vectorRank(),
                    existing == null ? 0 : existing.vectorScore(),
                    i + 1,
                    hit.score()));
        }
        return candidates;
    }

    private List<RetrievedChunk> toRetrievedChunks(List<Document> hits,
                                                   RetrievalMode mode,
                                                   Map<String, RerankService.Candidate> candidates) {
        List<RetrievedChunk> chunks = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            Document hit = hits.get(i);
            int index = i + 1;
            double score = round(scoreOf(hit));
            chunks.add(new RetrievedChunk(
                    index,
                    hit.getText(),
                    score,
                    toSourceRef(index, hit, score, mode, candidates.get(keyOf(hit)))));
        }
        return chunks;
    }

    private SourceRef toSourceRef(int index,
                                  Document document,
                                  double score,
                                  RetrievalMode mode,
                                  RerankService.Candidate candidate) {
        String source = stringMetadata(document, "source", "未知文档");
        int chunk = intMetadata(document, "chunk", -1);
        String heading = stringMetadata(document, "heading", null);
        return new SourceRef(
                "[" + index + "]",
                source,
                chunk,
                heading,
                score,
                snippet(document.getText()),
                location(source, chunk, heading),
                candidate == null || candidate.vectorRank() == null
                        ? (mode == RetrievalMode.VECTOR ? score : null)
                        : round(candidate.vectorScore()),
                candidate == null || candidate.lexicalRank() == null
                        ? null
                        : round(candidate.lexicalScore()),
                mode.wireName());
    }

    /** 「员工手册.md · 员工手册 > 年假 · 第 2 段」这样一眼能定位到原文的字符串。 */
    static String location(String source, int chunk, String heading) {
        StringBuilder location = new StringBuilder(source == null || source.isBlank() ? "未知文档" : source);
        if (heading != null && !heading.isBlank()) {
            location.append(" · ").append(heading);
        }
        if (chunk >= 0) {
            location.append(" · 第 ").append(chunk + 1).append(" 段");
        }
        return location.toString();
    }

    static String snippet(String text) {
        if (text == null) {
            return "";
        }
        String flattened = text.replaceAll("\\s+", " ").trim();
        return flattened.length() <= SNIPPET_LENGTH
                ? flattened
                : flattened.substring(0, SNIPPET_LENGTH) + "...";
    }

    /** 优先用 Spring AI 返回的分数，取不到时回落到元数据里的 score。 */
    static double scoreOf(Document document) {
        Double score = document.getScore();
        if (score != null) {
            return score;
        }
        Object metadataScore = document.getMetadata().get("score");
        if (metadataScore instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0;
    }

    private static String keyOf(Document document) {
        return stringMetadata(document, "source", "") + '\u0000'
                + intMetadata(document, "chunk", -1);
    }

    private static String stringMetadata(Document document, String key, String fallback) {
        Object value = document.getMetadata().get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static int intMetadata(Document document, String key, int fallback) {
        Object value = document.getMetadata().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // 元数据格式异常时用兜底值，不影响主流程
            }
        }
        return fallback;
    }

    private static String escape(String value) {
        return value.replace("'", "''");
    }

    private static double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }
}
