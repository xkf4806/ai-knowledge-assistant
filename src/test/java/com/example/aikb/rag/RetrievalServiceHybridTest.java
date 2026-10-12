package com.example.aikb.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalServiceHybridTest {

    @Test
    void rerankLetsExactKeywordCandidateBeatGenericVectorHit() {
        VectorStore vectorStore = mock(VectorStore.class);
        Document generic = document(
                "refund-policy.md", 0, "适用条件",
                "客户在收到商品后可以申请无理由退货。", 0.40);
        Document exact = document(
                "orders.md", 0, "订单退款条件",
                "订单 A1001 已完成且超过 7 天，不支持无理由退货。", 0.20);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(generic, exact));

        RagProperties properties = new RagProperties();
        properties.setTopK(2);
        properties.setCandidateK(5);
        properties.setRetrievalMode(RetrievalMode.HYBRID_RERANK);
        Bm25LexicalIndex lexicalIndex = new Bm25LexicalIndex();
        lexicalIndex.replaceSource("orders.md", List.of(exact));

        RetrievalService service = new RetrievalService(
                vectorStore, properties, lexicalIndex, new RerankService(properties));

        List<RetrievedChunk> hits = service.retrieve(
                "订单 A1001 能退吗", null, RetrievalMode.HYBRID_RERANK);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).sourceRef().getSource()).isEqualTo("orders.md");
        assertThat(hits.get(0).sourceRef().getRetrievalMode()).isEqualTo("hybrid-rerank");
        assertThat(hits.get(0).sourceRef().getVectorScore()).isNotNull();
        assertThat(hits.get(0).sourceRef().getLexicalScore()).isNotNull();
    }

    private static Document document(String source, int chunk, String heading, String text, double score) {
        return Document.builder()
                .text(text)
                .metadata(Map.of("source", source, "chunk", chunk, "heading", heading))
                .score(score)
                .build();
    }
}
