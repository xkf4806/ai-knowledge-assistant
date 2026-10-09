package com.example.aikb.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档摄取：读取文档 -> 分块 -> 写入向量库。
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final VectorStore vectorStore;
    private final RagProperties properties;

    public IngestionService(VectorStore vectorStore, RagProperties properties) {
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    public int ingestSampleDocs() {
        List<Document> documents = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources(properties.getSampleDocs());
            for (Resource resource : resources) {
                String text = resource.getContentAsString(StandardCharsets.UTF_8);
                String source = resource.getFilename();
                List<String> chunks = SimpleTextSplitter.split(
                        text, properties.getChunkSize(), properties.getChunkOverlap());
                for (int i = 0; i < chunks.size(); i++) {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("source", source);
                    metadata.put("chunk", i);
                    documents.add(new Document(chunks.get(i), metadata));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取示例文档失败", e);
        }

        if (!documents.isEmpty()) {
            vectorStore.add(documents);
        }
        log.info("已导入 {} 个文档片段", documents.size());
        return documents.size();
    }
}
