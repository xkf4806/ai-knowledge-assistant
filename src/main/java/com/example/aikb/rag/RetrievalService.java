package com.example.aikb.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量检索服务（第 3 周学习重点：向量检索工程化）。
 *
 * <p>把「构建检索请求 → 相似度检索 → 组装带来源的命中片段」从 {@link RagService} 里拆出来，
 * 一方面职责更单一，另一方面它不依赖大模型，可以用假的 {@link VectorStore} 做单元测试。
 *
 * <p>支持两点工程化的检索能力：
 * <ul>
 *   <li>{@code topK}：返回片段数量由 {@code app.rag.top-k} 控制，越大上下文越全但 token 越贵；</li>
 *   <li>元数据过滤：按 {@code source}（文件名）限定检索范围，避免不同文档相互污染。</li>
 * </ul>
 */
@Slf4j
@Service
public class RetrievalService {

    /** 返回给前端的片段摘要长度，够看清依据即可，避免响应体过大。 */
    private static final int SNIPPET_LENGTH = 120;

    private final VectorStore vectorStore;
    private final RagProperties properties;

    public RetrievalService(VectorStore vectorStore, RagProperties properties) {
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    /**
     * 检索与问题最相关的片段。
     *
     * @param question     用户问题
     * @param sourceFilter 可选：只在该文件名范围内检索（元数据过滤）；为空表示全库检索
     */
    public List<RetrievedChunk> retrieve(String question, String sourceFilter) {
        SearchRequest.Builder request = SearchRequest.builder()
                .query(question)
                .topK(properties.getTopK());
        if (sourceFilter != null && !sourceFilter.isBlank()) {
            request.filterExpression("source == '" + escape(sourceFilter.trim()) + "'");
        }

        List<Document> hits = vectorStore.similaritySearch(request.build());
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }

        List<RetrievedChunk> chunks = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            Document hit = hits.get(i);
            int index = i + 1;
            double score = round(scoreOf(hit));
            chunks.add(new RetrievedChunk(index, hit.getText(), score, toSourceRef(index, hit, score)));
        }
        log.info("检索到 {} 个片段{}", chunks.size(),
                sourceFilter == null || sourceFilter.isBlank() ? "" : "（限定文档：" + sourceFilter + "）");
        return chunks;
    }

    private SourceRef toSourceRef(int index, Document document, double score) {
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
                location(source, chunk, heading));
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

    /** 优先用 Spring AI 返回的分数，取不到时回落到元数据里的 score/distance。 */
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
