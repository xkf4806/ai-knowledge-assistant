package com.example.aikb.rag;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 引用溯源信息（第 3 周学习重点）：把检索命中的片段还原成「哪份文档、哪一节、第几段」。
 *
 * <p>{@code label} 与生成答案里的 {@code [1]、[2]} 一一对应，前端据此把答案中的引用角标
 * 映射到原始片段，让答案可追溯、可核对。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceRef {

    /** 上下文编号，如 {@code [1]} */
    private String label;

    /** 来源文件名，如 {@code employee-handbook.md} */
    private String source;

    /** 片段在该文档中的序号（0 基） */
    private int chunk;

    /** 标题路径，如 {@code 员工手册 > 年假}；无标题时为 null */
    private String heading;

    /** 相似度分数（余弦相似度，越大越相关） */
    private double score;

    /** 命中片段摘要，便于前端直接展示「原文依据」 */
    private String snippet;

    /** 人类可读定位，如 {@code employee-handbook.md · 员工手册 > 年假 · 第 2 段} */
    private String location;

    /** 向量检索原始分数（仅 hybrid / hybrid-rerank 模式可能为空） */
    private Double vectorScore;

    /** BM25 关键词检索原始分数（仅 hybrid / hybrid-rerank 模式可能为空） */
    private Double lexicalScore;

    /** 本条来源采用的检索模式 */
    private String retrievalMode;

    public SourceRef(String label, String source, int chunk, String heading,
                     double score, String snippet, String location) {
        this(label, source, chunk, heading, score, snippet, location, null, null, null);
    }
}
