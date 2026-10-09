package com.example.aikb.config;

import com.example.aikb.rag.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;

@Slf4j
@Configuration
public class RagConfig {

    /**
     * 默认实现：内存向量库。进程重启后索引清空，适合本地跑通链路与跑测试（无需外部依赖）。
     *
     * <p>第 3 周加了 pgvector 之后，内存实现仍保留为默认，是为了让「没有数据库」的开发/测试环境
     * 依然能启动；要演示持久化能力，把 {@code app.rag.vector-store} 切成 {@code pgvector} 即可。
     */
    @Bean
    @ConditionalOnProperty(name = "app.rag.vector-store", havingValue = "memory", matchIfMissing = true)
    public VectorStore memoryVectorStore(EmbeddingModel embeddingModel) {
        log.warn("当前使用内存向量库 SimpleVectorStore：重启后索引会丢失。"
                + "需要持久化请设置 app.rag.vector-store=pgvector。");
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    /**
     * 持久化实现：pgvector。Bean 创建时会自动建表（initializeSchema），
     * 所以要求 PostgreSQL 已开启 pgvector 扩展（docker-compose.yml 中的镜像自带）。
     *
     * <p>显式指定 {@code dimensions} 而不是让 Spring AI 现场调用 embedding 接口探测，
     * 这样应用启动阶段不产生任何模型调用，也不会因为探测失败而卡住。
     */
    @Bean
    @ConditionalOnProperty(name = "app.rag.vector-store", havingValue = "pgvector")
    public VectorStore pgVectorStore(JdbcTemplate jdbcTemplate,
                                     EmbeddingModel embeddingModel,
                                     RagProperties properties) {
        log.info("使用 pgvector 持久化向量库：table={}, dimensions={}, distance=cosine",
                properties.getVectorTableName(), properties.getEmbeddingDimensions());
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName(properties.getVectorTableName())
                .dimensions(properties.getEmbeddingDimensions())
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .initializeSchema(true)
                .build();
    }
}
