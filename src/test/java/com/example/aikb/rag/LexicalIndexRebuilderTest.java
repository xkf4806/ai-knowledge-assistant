package com.example.aikb.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * pgvector 模式下重启后，关键词索引要能从库里重建回来。
 */
class LexicalIndexRebuilderTest {

    @Test
    void rebuildsKeywordIndexFromPersistedVectors() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        given(jdbcTemplate.queryForList(anyString())).willReturn(List.of(
                Map.of("content", "年假上限为 15 天。", "metadata", "{\"source\":\"employee-handbook.md\",\"chunk\":0}"),
                Map.of("content", "满 3 年后每满 1 年增加 1 天。", "metadata", "{\"source\":\"employee-handbook.md\",\"chunk\":1}"),
                Map.of("content", "VPN 账号有效期 90 天。", "metadata", "{\"source\":\"it-support.txt\",\"chunk\":0}")));

        Bm25LexicalIndex index = new Bm25LexicalIndex();
        RagProperties properties = new RagProperties();
        properties.setVectorStore(VectorStoreType.PGVECTOR);

        new LexicalIndexRebuilder(jdbcTemplate, properties, index, new ObjectMapper()).rebuild();

        assertThat(index.size()).isEqualTo(3);
        assertThat(index.search("VPN 有效期", null, 5)).isNotEmpty();
    }

    @Test
    void doesNothingForInMemoryVectorStore() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RagProperties properties = new RagProperties();
        properties.setVectorStore(VectorStoreType.MEMORY);

        new LexicalIndexRebuilder(jdbcTemplate, properties, new Bm25LexicalIndex(), new ObjectMapper()).rebuild();

        verify(jdbcTemplate, never()).queryForList(anyString());
    }

    @Test
    void ignoresRowsWithoutSourceOrContent() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        given(jdbcTemplate.queryForList(anyString())).willReturn(List.of(
                Map.of("content", "没有来源的片段", "metadata", "{}"),
                Map.of("content", "", "metadata", "{\"source\":\"x.md\"}")));

        Bm25LexicalIndex index = new Bm25LexicalIndex();
        RagProperties properties = new RagProperties();
        properties.setVectorStore(VectorStoreType.PGVECTOR);

        new LexicalIndexRebuilder(jdbcTemplate, properties, index, new ObjectMapper()).rebuild();

        assertThat(index.size()).isZero();
    }
}
