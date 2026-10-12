package com.example.aikb.rag;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量 BM25 关键词索引。
 *
 * <p>中文没有天然空格，这里采用「CJK 连续串 → 单字 + 二元词」的朴素切词方式，
 * 英文与数字则按单词切分。实现不依赖额外分词服务，适合本地演示与单元测试。
 *
 * <p>向量检索擅长语义相近，BM25 擅长订单号、专有名词、数字条件这类精确词项。
 * 两路召回合并后再重排，通常能比单走向量检索更稳。
 */
@Service
public class Bm25LexicalIndex implements LexicalIndex {

    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("[\\p{IsHan}]+|[a-z0-9_]+", Pattern.CASE_INSENSITIVE);
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final ConcurrentMap<String, IndexedDocument> documents = new ConcurrentHashMap<>();
    private final AtomicReference<IndexSnapshot> snapshot = new AtomicReference<>(IndexSnapshot.empty());

    @Override
    public void replaceSource(String source, List<Document> sourceDocuments) {
        if (source == null || source.isBlank()) {
            return;
        }
        removeSource(source);
        for (Document document : sourceDocuments) {
            if (document == null || document.getText() == null || document.getText().isBlank()) {
                continue;
            }
            List<String> tokens = tokenize(document.getText());
            Map<String, Integer> termFrequency = new HashMap<>();
            for (String token : tokens) {
                termFrequency.merge(token, 1, Integer::sum);
            }
            IndexedDocument indexed = new IndexedDocument(
                    keyOf(document, source),
                    document,
                    termFrequency,
                    tokens.size());
            documents.put(indexed.key(), indexed);
        }
        rebuildSnapshot();
    }

    @Override
    public void removeSource(String source) {
        if (source == null || source.isBlank()) {
            return;
        }
        documents.values().removeIf(indexed -> source.equals(sourceOf(indexed.document(), null)));
        rebuildSnapshot();
    }

    @Override
    public List<LexicalHit> search(String query, String sourceFilter, int limit) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        Set<String> queryTerms = new LinkedHashSet<>(tokenize(query));
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        IndexSnapshot current = snapshot.get();
        List<ScoredDocument> scored = new ArrayList<>();
        for (IndexedDocument indexed : documents.values()) {
            String source = sourceOf(indexed.document(), null);
            if (sourceFilter != null && !sourceFilter.isBlank() && !sourceFilter.equals(source)) {
                continue;
            }
            double score = score(queryTerms, indexed, current);
            if (score > 0) {
                scored.add(new ScoredDocument(indexed.document(), score));
            }
        }

        scored.sort(Comparator.comparingDouble(ScoredDocument::score).reversed()
                .thenComparing(scoredDocument -> sourceOf(scoredDocument.document(), ""))
                .thenComparingInt(scoredDocument -> chunkOf(scoredDocument.document())));
        return scored.stream()
                .limit(limit)
                .map(hit -> new LexicalHit(hit.document(), round(hit.score())))
                .toList();
    }

    @Override
    public int size() {
        return documents.size();
    }

    /**
     * 供 BM25 与重排器共用的切词逻辑。
     *
     * <p>中文连续串主要产生相邻二元词，例如「年假上限」会得到
     * {@code 年假 / 假上 / 上限}。只有本身长度为 1 的连续串才产生单字，
     * 避免「商 / 品 / 退」这类高频单字把泛化片段顶上去。
     */
    public static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group();
            if (isAsciiToken(token)) {
                tokens.add(token);
                continue;
            }
            if (token.length() == 1) {
                tokens.add(token);
                continue;
            }
            for (int i = 0; i + 2 <= token.length(); i++) {
                tokens.add(token.substring(i, i + 2));
            }
        }
        return tokens;
    }

    private static double score(Set<String> queryTerms, IndexedDocument document, IndexSnapshot snapshot) {
        if (snapshot.totalDocuments() == 0 || document.length() == 0) {
            return 0;
        }
        double score = 0;
        for (String term : queryTerms) {
            int documentFrequency = snapshot.documentFrequency().getOrDefault(term, 0);
            if (documentFrequency == 0) {
                continue;
            }
            int termFrequency = document.termFrequency().getOrDefault(term, 0);
            if (termFrequency == 0) {
                continue;
            }
            double idf = Math.log(1 + (snapshot.totalDocuments() - documentFrequency + 0.5)
                    / (documentFrequency + 0.5));
            double denominator = termFrequency + K1
                    * (1 - B + B * document.length() / snapshot.averageDocumentLength());
            score += idf * termFrequency * (K1 + 1) / denominator;
        }
        return score;
    }

    private void rebuildSnapshot() {
        Map<String, Integer> documentFrequency = new HashMap<>();
        int totalLength = 0;
        for (IndexedDocument document : documents.values()) {
            totalLength += document.length();
            for (String term : document.termFrequency().keySet()) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }
        int count = documents.size();
        double averageLength = count == 0 ? 0 : (double) totalLength / count;
        snapshot.set(new IndexSnapshot(Map.copyOf(documentFrequency), count, averageLength));
    }

    private static String keyOf(Document document, String source) {
        return sourceOf(document, source) + '\u0000' + chunkOf(document);
    }

    private static String sourceOf(Document document, String fallback) {
        Object value = document.getMetadata().get("source");
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static int chunkOf(Document document) {
        Object value = document.getMetadata().get("chunk");
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // 元数据异常时使用固定兜底值，不影响检索主流程。
            }
        }
        return -1;
    }

    private static boolean isAsciiToken(String token) {
        return token.chars().allMatch(value -> value < 128);
    }

    private static double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    private record IndexedDocument(String key,
                                   Document document,
                                   Map<String, Integer> termFrequency,
                                   int length) {
    }

    private record IndexSnapshot(Map<String, Integer> documentFrequency,
                                 int totalDocuments,
                                 double averageDocumentLength) {

        private static IndexSnapshot empty() {
            return new IndexSnapshot(Map.of(), 0, 0);
        }
    }

    private record ScoredDocument(Document document, double score) {
    }
}
