package com.example.aikb.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 启动时从持久化向量库重建关键词索引。
 *
 * <p>此前 BM25 索引是纯内存、只在导入时构建：重启之后向量数据还在（pgvector），
 * 关键词召回却悄悄退化成「纯向量检索」，而且只在 {@code /api/rag/status} 的
 * {@code lexicalIndexSize} 里能看出来。这里在应用就绪后把库里的片段读回来、
 * 按来源重新喂给 {@link LexicalIndex}，让「持久化」这件事对检索效果也成立。
 *
 * <p>只在 {@code app.rag.vector-store=pgvector} 时生效；内存向量库重启即清空，没有可重建的数据。
 * 重建过程只读数据库、不调用任何模型接口，所以启动不产生费用。
 */
@Slf4j
@Component
public class LexicalIndexRebuilder {

    /** 表名来自配置而不是用户输入，这里仍做一次白名单校验，避免拼出意外的 SQL。 */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final JdbcTemplate jdbcTemplate;
    private final RagProperties properties;
    private final LexicalIndex lexicalIndex;
    private final ObjectMapper objectMapper;

    public LexicalIndexRebuilder(JdbcTemplate jdbcTemplate,
                                 RagProperties properties,
                                 LexicalIndex lexicalIndex,
                                 ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.lexicalIndex = lexicalIndex;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void rebuild() {
        if (properties.getVectorStore() != VectorStoreType.PGVECTOR) {
            return;
        }
        String table = properties.getVectorTableName();
        if (table == null || !SAFE_IDENTIFIER.matcher(table).matches()) {
            log.warn("向量表名不合法，跳过关键词索引重建：{}", table);
            return;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "select content, metadata::text as metadata from " + table);
            Map<String, List<Document>> bySource = groupBySource(rows);
            bySource.forEach(lexicalIndex::replaceSource);
            log.info("已从向量库重建关键词索引：{} 个来源、{} 个片段",
                    bySource.size(), bySource.values().stream().mapToInt(List::size).sum());
        } catch (Exception e) {
            // 重建失败只影响关键词召回（向量检索照常），不要让应用起不来。
            log.warn("从向量库重建关键词索引失败，本轮关键词召回将为空：{}", e.toString());
        }
    }

    private Map<String, List<Document>> groupBySource(List<Map<String, Object>> rows) {
        Map<String, List<Document>> bySource = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object content = row.get("content");
            if (content == null || String.valueOf(content).isBlank()) {
                continue;
            }
            Map<String, Object> metadata = parseMetadata(row.get("metadata"));
            Object source = metadata.get("source");
            if (source == null || String.valueOf(source).isBlank()) {
                continue;
            }
            bySource.computeIfAbsent(String.valueOf(source), key -> new ArrayList<>())
                    .add(new Document(String.valueOf(content), metadata));
        }
        return bySource;
    }

    private Map<String, Object> parseMetadata(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(String.valueOf(raw), new TypeReference<>() {
            });
        } catch (Exception e) {
            log.debug("片段元数据解析失败，已跳过：{}", e.getMessage());
            return Map.of();
        }
    }
}
