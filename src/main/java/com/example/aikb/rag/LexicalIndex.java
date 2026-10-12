package com.example.aikb.rag;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 关键词检索索引抽象。
 *
 * <p>默认实现是内存 BM25；摄取新文档时按 source 整体替换，避免重复导入后关键词索引叠加旧片段。
 */
public interface LexicalIndex {

    /** 用一批片段替换某个 source 的全部旧索引。 */
    void replaceSource(String source, List<Document> documents);

    /** 删除某个 source 的关键词索引。 */
    void removeSource(String source);

    /** 关键词检索，可按 source 限定范围。 */
    List<LexicalHit> search(String query, String sourceFilter, int limit);

    /** 当前索引片段数，用于状态展示与测试断言。 */
    int size();
}
