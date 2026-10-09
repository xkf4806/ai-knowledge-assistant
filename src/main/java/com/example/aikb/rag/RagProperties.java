package com.example.aikb.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    /** 每个文档片段的目标字符数 */
    private int chunkSize = 300;

    /** 相邻片段的重叠字符数，避免语义在边界被切断 */
    private int chunkOverlap = 50;

    /** 检索返回的片段数量 */
    private int topK = 4;

    /** 示例文档位置，支持 Spring Resource 通配符 */
    private String sampleDocs = "classpath:sample-docs/*.md";
}
