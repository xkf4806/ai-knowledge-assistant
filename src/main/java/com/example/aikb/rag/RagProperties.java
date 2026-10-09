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

    /** 示例文档位置，支持 Spring Resource 通配符 */
    private String sampleDocs = "classpath:sample-docs/*";

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
