package com.example.aikb.rag;

import com.example.aikb.rag.parser.DocumentParser;
import com.example.aikb.rag.parser.ParsedDocument;
import com.example.aikb.rag.splitter.ChunkingOptions;
import com.example.aikb.rag.splitter.ChunkingStrategy;
import com.example.aikb.rag.splitter.TextChunk;
import com.example.aikb.rag.splitter.TextSplitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档摄取：解析文档 -> 分块 -> 批量写入向量库。
 * 支持 PDF / Word / Markdown 等多格式，并把分块策略做成可配置参数。
 */
@Slf4j
@Service
public class IngestionService {

    /** 预览片段截断长度，够看清切分边界即可 */
    private static final int PREVIEW_LENGTH = 80;

    /**
     * 单次写入向量库的片段数。
     * 大文档一次性提交会让一次 embedding 请求又大又慢，失败时还看不出进行到哪；
     * 分批写入既能打进度日志，也能把「哪一批失败」说清楚。
     */
    private static final int WRITE_BATCH_SIZE = 32;

    private final VectorStore vectorStore;
    private final RagProperties properties;
    private final DocumentParser documentParser;
    private final TextSplitter textSplitter;
    private final LexicalIndex lexicalIndex;

    public IngestionService(VectorStore vectorStore, RagProperties properties,
                            DocumentParser documentParser, TextSplitter textSplitter,
                            LexicalIndex lexicalIndex) {
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.documentParser = documentParser;
        this.textSplitter = textSplitter;
        this.lexicalIndex = lexicalIndex;
    }

    /** 导入 classpath:sample-docs 下的示例文档，可用参数覆盖默认分块策略。 */
    public IngestResponse ingestSampleDocs(String strategy, Integer chunkSize, Integer overlap) {
        ChunkingOptions options = properties.resolveOptions(strategy, chunkSize, overlap);
        List<Document> documents = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources(properties.getSampleDocs());
            for (Resource resource : resources) {
                if (resource.getFilename() == null) {
                    continue;
                }
                try (InputStream in = resource.getInputStream()) {
                    ParsedDocument parsed = documentParser.parse(in, resource.getFilename());
                    documents.addAll(toDocuments(parsed, options));
                    sources.add(parsed.source());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取示例文档失败", e);
        }
        return index(documents, sources, options);
    }

    /** 解析并索引一个上传的文档。 */
    public IngestResponse ingest(InputStream inputStream, String filename, String strategy,
                                 Integer chunkSize, Integer overlap) {
        ChunkingOptions options = properties.resolveOptions(strategy, chunkSize, overlap);
        ParsedDocument parsed = documentParser.parse(inputStream, filename);
        return index(toDocuments(parsed, options), List.of(parsed.source()), options);
    }

    /** 只做解析 + 分块预览，不调用 embedding，方便对比不同策略与参数。 */
    public PreviewResponse preview(InputStream inputStream, String filename,
                                   List<ChunkingStrategy> strategies, ChunkingOptions options) {
        ParsedDocument parsed = documentParser.parse(inputStream, filename);
        List<StrategyPreview> previews = new ArrayList<>();
        for (ChunkingStrategy strategy : strategies) {
            List<TextChunk> chunks = textSplitter.split(parsed.text(), options.withStrategy(strategy));
            previews.add(new StrategyPreview(
                    strategy.wireName(),
                    options.chunkSize(),
                    options.overlap(),
                    chunks.size(),
                    toChunkPreviews(chunks)));
        }
        return new PreviewResponse(parsed.source(), parsed.contentType(), parsed.text().length(), previews);
    }

    private List<Document> toDocuments(ParsedDocument parsed, ChunkingOptions options) {
        List<TextChunk> chunks = textSplitter.split(parsed.text(), options);
        List<Document> documents = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", parsed.source());
            metadata.put("contentType", parsed.contentType());
            metadata.put("chunk", i);
            metadata.put("strategy", options.strategy().wireName());
            if (chunk.heading() != null) {
                metadata.put("heading", chunk.heading());
            }
            documents.add(new Document(chunk.text(), metadata));
        }
        return documents;
    }

    private List<ChunkPreview> toChunkPreviews(List<TextChunk> chunks) {
        List<ChunkPreview> previews = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            String text = chunks.get(i).text();
            String preview = text.length() <= PREVIEW_LENGTH
                    ? text
                    : text.substring(0, PREVIEW_LENGTH) + "...";
            previews.add(new ChunkPreview(i, chunks.get(i).heading(), text.length(), preview));
        }
        return previews;
    }

    private IngestResponse index(List<Document> documents, List<String> sources, ChunkingOptions options) {
        // 按来源先删后写，保证重复导入同一文档时是「替换」而不是「叠加」，
        // 否则同一份文档会被索引多遍，检索结果里出现大量重复片段。
        for (String source : sources) {
            vectorStore.delete("source == '" + escape(source) + "'");
        }
        if (!documents.isEmpty()) {
            writeDocuments(documents, sources);
        }
        // 关键词索引与向量库保持同一份片段集合，供 BM25 混合召回使用。
        for (String source : sources) {
            List<Document> sourceDocuments = documents.stream()
                    .filter(document -> source.equals(document.getMetadata().get("source")))
                    .toList();
            lexicalIndex.replaceSource(source, sourceDocuments);
        }
        log.info("已索引 {} 个文档、{} 个片段，策略={}", sources.size(), documents.size(), options.strategy());
        return new IngestResponse(sources.size(), documents.size(), options.strategy().wireName(), sources);
    }

    /**
     * 分批写入向量库（写入时会调用 embedding 接口）。
     *
     * <p>以前写失败只会冒出一个英文 I/O 异常，前端看到的是「一直转圈」。
     * 现在把失败原因翻译成一句可读的话，并明确告诉调用方「旧片段已经被删掉了，需要重新索引」——
     * 这里的语义是「替换」，不是「追加」，这一点必须说清楚。
     */
    private void writeDocuments(List<Document> documents, List<String> sources) {
        try {
            for (int from = 0; from < documents.size(); from += WRITE_BATCH_SIZE) {
                int to = Math.min(from + WRITE_BATCH_SIZE, documents.size());
                vectorStore.add(documents.subList(from, to));
                log.info("写入向量库进度：{}/{} 个片段", to, documents.size());
            }
        } catch (RuntimeException e) {
            log.error("写入向量库失败：来源={}，片段数={}，原因={}", sources, documents.size(), e.toString(), e);
            throw new IllegalStateException(
                    "写入向量库失败，通常是 embedding 接口不可用（网络不通 / 超时 / 额度或 Key 有问题）："
                            + rootMessage(e)
                            + "。来源 " + sources + " 的旧片段已被按来源删除，请排查后重新索引。", e);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            message = cause.getClass().getSimpleName();
        }
        return message.length() <= 200 ? message : message.substring(0, 200) + "...";
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("'", "''");
    }
}
