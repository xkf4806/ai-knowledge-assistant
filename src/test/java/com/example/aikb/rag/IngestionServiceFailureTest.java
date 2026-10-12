package com.example.aikb.rag;

import com.example.aikb.rag.parser.TikaDocumentParser;
import com.example.aikb.rag.splitter.TextSplitter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * upload/ingest 失败时要给出一句能看懂的话，大文档要分批写入（能看到进度）。
 */
class IngestionServiceFailureTest {

    @Test
    void reportsReadableErrorWhenEmbeddingWriteFails() {
        VectorStore vectorStore = mock(VectorStore.class);
        doThrow(new RuntimeException(
                "I/O error on POST request for \"https://api.siliconflow.cn/v1/embeddings\": Connection timed out"))
                .when(vectorStore).add(anyList());

        IngestionService service = service(vectorStore);

        assertThatThrownBy(() -> service.ingest(stream("# 标题\n\n正文内容。\n"), "failing.md", null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("写入向量库失败")
                .hasMessageContaining("Connection timed out")
                .hasMessageContaining("failing.md")
                .hasMessageContaining("重新索引");
    }

    @Test
    void writesLargeDocumentsInBatches() {
        VectorStore vectorStore = mock(VectorStore.class);
        IngestionService service = service(vectorStore);

        // 80 段、每段 60 字符，按 40 字符切分 -> 远超 32 的批大小
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            text.append("这是第 ").append(i).append(" 段用于测试批量写入的内容，长度大约六十个字符左右，凑够切分粒度。\n\n");
        }

        IngestResponse response = service.ingest(stream(text.toString()), "many.txt", "fixed", 40, 0);

        verify(vectorStore, atLeast(3)).add(anyList());
        verify(vectorStore).delete("source == 'many.txt'");
        org.assertj.core.api.Assertions.assertThat(response.getChunks()).isGreaterThan(32);
    }

    private static IngestionService service(VectorStore vectorStore) {
        return new IngestionService(vectorStore, new RagProperties(),
                new TikaDocumentParser(), new TextSplitter(), new Bm25LexicalIndex());
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
