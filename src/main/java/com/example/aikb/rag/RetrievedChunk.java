package com.example.aikb.rag;

/**
 * 一次检索命中的片段：带上下文编号、原文、相似度分数与结构化来源。
 *
 * @param index     上下文里的编号（1 基），对应答案中的 {@code [index]}
 * @param text      命中的原文片段
 * @param score     相似度分数（越大越相关）
 * @param sourceRef 结构化来源，用于返回给前端做溯源
 */
public record RetrievedChunk(int index, String text, double score, SourceRef sourceRef) {

    public String label() {
        return "[" + index + "]";
    }
}
