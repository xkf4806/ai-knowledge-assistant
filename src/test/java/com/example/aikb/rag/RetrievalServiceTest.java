package com.example.aikb.rag;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalServiceTest {

    @Test
    void buildsSourceRefWithLocationAndScore() {
        VectorStore vectorStore = mock(VectorStore.class);
        Document hit = Document.builder()
                .text("入职满 1 年的员工，每年享有 10 天带薪年假。")
                .metadata(Map.of(
                        "source", "employee-handbook.md",
                        "chunk", 1,
                        "heading", "员工手册 > 年假"))
                .score(0.83125)
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit));
        RetrievalService service = new RetrievalService(
                vectorStore, properties(4), new Bm25LexicalIndex(), new RerankService(properties(4)));

        List<RetrievedChunk> chunks = service.retrieve("年假有几天", null);

        assertThat(chunks).hasSize(1);
        RetrievedChunk chunk = chunks.get(0);
        assertThat(chunk.index()).isEqualTo(1);
        assertThat(chunk.label()).isEqualTo("[1]");
        assertThat(chunk.score()).isEqualTo(0.8313);

        SourceRef source = chunk.sourceRef();
        assertThat(source.getLabel()).isEqualTo("[1]");
        assertThat(source.getSource()).isEqualTo("employee-handbook.md");
        assertThat(source.getChunk()).isEqualTo(1);
        assertThat(source.getHeading()).isEqualTo("员工手册 > 年假");
        assertThat(source.getScore()).isEqualTo(0.8313);
        assertThat(source.getLocation()).isEqualTo("employee-handbook.md · 员工手册 > 年假 · 第 2 段");
        assertThat(source.getSnippet()).contains("10 天带薪年假");
    }

    @Test
    void appliesSourceFilterAsMetadataExpression() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        RetrievalService service = new RetrievalService(
                vectorStore, properties(3), new Bm25LexicalIndex(), new RerankService(properties(3)));
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);

        service.retrieve("报销流程", "refund-policy.md");
        verify(vectorStore).similaritySearch(captor.capture());

        SearchRequest request = captor.getValue();
        assertThat(request.getTopK()).isEqualTo(3);
        assertThat(request.hasFilterExpression()).isTrue();
        assertThat(request.getFilterExpression().toString()).contains("refund-policy.md");
    }

    @Test
    void returnsEmptyWhenNothingMatches() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        RetrievalService service = new RetrievalService(
                vectorStore, properties(3), new Bm25LexicalIndex(), new RerankService(properties(3)));

        assertThat(service.retrieve("不存在的问题", null)).isEmpty();
    }

    private static RagProperties properties(int topK) {
        RagProperties properties = new RagProperties();
        properties.setTopK(topK);
        properties.setRetrievalMode(RetrievalMode.VECTOR);
        return properties;
    }
}
