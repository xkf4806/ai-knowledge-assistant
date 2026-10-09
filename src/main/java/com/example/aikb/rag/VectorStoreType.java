package com.example.aikb.rag;

import java.util.Locale;

/**
 * 向量库实现类型（第 3 周学习重点：向量检索工程化）。
 * <ul>
 *   <li>{@code MEMORY}：{@code SimpleVectorStore}，进程内存，重启即丢，适合本地快速跑通。</li>
 *   <li>{@code PGVECTOR}：{@code PgVectorStore}，落库持久化，支持重启后继续检索，是演示/部署形态。</li>
 * </ul>
 * 通过 {@code app.rag.vector-store} 配置切换，上层检索逻辑对实现无感。
 */
public enum VectorStoreType {
    MEMORY,
    PGVECTOR;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
