package com.example.aikb.rag;

import com.example.aikb.rag.splitter.ChunkingOptions;
import com.example.aikb.rag.splitter.ChunkingStrategy;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    /** 每个文档片段的目标字符数 */
    private int chunkSize = 300;

    /** 相邻片段的重叠字符数，避免语义在边界被切断 */
    private int chunkOverlap = 50;

    /** 默认分块策略：fixed / heading / paragraph（第 2 周新增） */
    private ChunkingStrategy chunkStrategy = ChunkingStrategy.HEADING;

    /** 检索返回的片段数量 */
    private int topK = 4;

    /** 默认检索模式：vector（基线）/ hybrid（RRF）/ hybrid-rerank（第 4 周默认） */
    private RetrievalMode retrievalMode = RetrievalMode.HYBRID_RERANK;

    /**
     * 混合召回候选数。最终展示仍只保留 topK，但两路先各取更多候选再融合，
     * 给关键词命中和重排留出翻盘空间。
     */
    private int candidateK = 12;

    /** 加权 RRF 的两路权重：向量为主，BM25 负责关键词翻盘。 */
    private double rerankVectorWeight = 0.70;
    private double rerankLexicalWeight = 0.30;

    /** 覆盖率与标题匹配只做微小 tie-break，实际权重会再乘 FEATURE_SCALE。 */
    private double rerankCoverageWeight = 0.10;
    private double rerankHeadingWeight = 0.20;

    /** 示例文档位置，支持 Spring Resource 通配符 */
    private String sampleDocs = "classpath:sample-docs/*";

    /** 向量库实现：memory（默认，内存）/ pgvector（持久化） */
    private VectorStoreType vectorStore = VectorStoreType.MEMORY;

    /** embedding 维度，需与所用 embedding 模型一致（BAAI/bge-m3 为 1024） */
    private int embeddingDimensions = 1024;

    /** pgvector 表名，Schema 由 PgVectorStore 自动初始化 */
    private String vectorTableName = "ai_kb_vectors";

    /**
     * 把接口传入的可选参数解析成一次分块参数，缺省时回落到 application.yml 的配置。
     * 只给 chunkSize 而没给 overlap 时，把重叠长度收窄到 chunkSize 以内，避免误报参数错误。
     */
    public ChunkingOptions resolveOptions(String strategy, Integer chunkSize, Integer overlap) {
        ChunkingStrategy resolvedStrategy = (strategy == null || strategy.isBlank())
                ? this.chunkStrategy
                : ChunkingStrategy.from(strategy);
        int resolvedChunkSize = chunkSize == null ? this.chunkSize : chunkSize;
        int resolvedOverlap;
        if (overlap != null) {
            resolvedOverlap = overlap;
        } else {
            resolvedOverlap = Math.min(this.chunkOverlap, Math.max(resolvedChunkSize - 1, 0));
        }
        return new ChunkingOptions(resolvedStrategy, resolvedChunkSize, resolvedOverlap);
    }
}
