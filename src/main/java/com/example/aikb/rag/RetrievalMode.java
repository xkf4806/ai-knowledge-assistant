package com.example.aikb.rag;

import java.util.Locale;

/**
 * 检索模式（第 4 周新增）。
 *
 * <ul>
 *   <li>{@link #VECTOR}：只走向量检索，作为优化前的基线；</li>
 *   <li>{@link #HYBRID}：向量 + BM25 关键词召回，用 RRF 融合两路排名；</li>
 *   <li>{@link #HYBRID_RERANK}：先混合召回候选，再用确定性特征重排。</li>
 * </ul>
 */
public enum RetrievalMode {

    VECTOR("vector"),
    HYBRID("hybrid"),
    HYBRID_RERANK("hybrid-rerank");

    private final String wireName;

    RetrievalMode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static RetrievalMode from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("retrieval mode 不能为空");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (RetrievalMode mode : values()) {
            if (mode.wireName.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(
                "不支持的检索模式：" + value + "，可选 vector / hybrid / hybrid-rerank");
    }
}
