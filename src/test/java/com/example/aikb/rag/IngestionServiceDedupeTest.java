package com.example.aikb.rag;

import com.example.aikb.rag.parser.TikaDocumentParser;
import com.example.aikb.rag.splitter.TextSplitter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 用 mock 向量库替代真实 embedding 调用，验证「按来源先删后写」的重复导入去重逻辑。
 * 解析与分块走真实实现，所以也顺带覆盖了示例文档的读取。
 */
class IngestionServiceDedupeTest {

    @Test
    void deletesExistingSourceBeforeWritingChunks() {
        VectorStore vectorStore = mock(VectorStore.class);
        IngestionService service = new IngestionService(
                vectorStore, new RagProperties(), new TikaDocumentParser(), new TextSplitter(),
                new Bm25LexicalIndex());

        IngestResponse response = service.ingestSampleDocs(null, null, null);

        assertThat(response.getDocuments()).isEqualTo(3);
        assertThat(response.getChunks()).isGreaterThan(0);

        ArgumentCaptor<String> filters = ArgumentCaptor.forClass(String.class);
        verify(vectorStore, times(3)).delete(filters.capture());
        List<String> captured = filters.getAllValues();
        assertThat(captured).allSatisfy(filter -> assertThat(filter).startsWith("source == '"));
        assertThat(captured).anySatisfy(filter -> assertThat(filter).contains("employee-handbook.md"));
        assertThat(captured).anySatisfy(filter -> assertThat(filter).contains("refund-policy.md"));
        assertThat(captured).anySatisfy(filter -> assertThat(filter).contains("it-support.txt"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<org.springframework.ai.document.Document>> written =
                ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(written.capture());
        assertThat(written.getValue()).hasSize(response.getChunks());
    }
}
