package com.example.aikb.rag;

import com.example.aikb.chat.ConversationMemoryService;
import com.example.aikb.rag.splitter.ChunkingOptions;
import com.example.aikb.rag.splitter.ChunkingStrategy;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private static final List<ChunkingStrategy> ALL_STRATEGIES =
            List.of(ChunkingStrategy.FIXED, ChunkingStrategy.HEADING, ChunkingStrategy.PARAGRAPH);

    private final RagService ragService;
    private final IngestionService ingestionService;
    private final RagProperties properties;
    private final ConversationMemoryService memory;

    public RagController(RagService ragService, IngestionService ingestionService,
                         RagProperties properties, ConversationMemoryService memory) {
        this.ragService = ragService;
        this.ingestionService = ingestionService;
        this.properties = properties;
        this.memory = memory;
    }

    /**
     * 把 classpath:sample-docs/* 导入当前向量库（内存或 pgvector，取决于配置）。
     * 可用查询参数覆盖分块策略，例如 {@code ?strategy=paragraph&chunkSize=200&overlap=20}。
     */
    @PostMapping("/ingest")
    public IngestResponse ingest(@RequestParam(required = false) String strategy,
                                 @RequestParam(required = false) Integer chunkSize,
                                 @RequestParam(required = false) Integer overlap) {
        return ingestionService.ingestSampleDocs(strategy, chunkSize, overlap);
    }

    /** 上传真实文档（PDF / Word / Markdown / txt / HTML ...），解析、分块并写入向量库。 */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestResponse upload(@RequestParam("file") MultipartFile file,
                                 @RequestParam(required = false) String strategy,
                                 @RequestParam(required = false) Integer chunkSize,
                                 @RequestParam(required = false) Integer overlap) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        try (InputStream in = file.getInputStream()) {
            return ingestionService.ingest(in, file.getOriginalFilename(), strategy, chunkSize, overlap);
        } catch (IOException e) {
            throw new IllegalStateException("读取上传文件失败：" + file.getOriginalFilename(), e);
        }
    }

    /**
     * 只做解析 + 分块预览（不调用 embedding），用于零成本对比不同分块策略和参数。
     * {@code strategies} 缺省时一次返回 fixed / heading / paragraph 三种结果。
     */
    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PreviewResponse preview(@RequestParam("file") MultipartFile file,
                                   @RequestParam(required = false) String strategies,
                                   @RequestParam(required = false) Integer chunkSize,
                                   @RequestParam(required = false) Integer overlap) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        List<ChunkingStrategy> selected = (strategies == null || strategies.isBlank())
                ? ALL_STRATEGIES
                : ChunkingStrategy.parseList(strategies);
        ChunkingOptions options = properties.resolveOptions(null, chunkSize, overlap);
        try (InputStream in = file.getInputStream()) {
            return ingestionService.preview(in, file.getOriginalFilename(), selected, options);
        } catch (IOException e) {
            throw new IllegalStateException("读取上传文件失败：" + file.getOriginalFilename(), e);
        }
    }

    /** 基于知识库提问，可带 sessionId 续上多轮上下文，也可用 source 过滤到单份文档。 */
    @PostMapping("/ask")
    public RagAnswer ask(@RequestBody AskRequest request) {
        if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        return ragService.ask(request);
    }

    /** 清空一个会话的对话记忆。 */
    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, Object> clearSession(@PathVariable String sessionId) {
        memory.clear(sessionId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("cleared", true);
        return body;
    }

    /** 当前检索/记忆的运行参数，便于演示与排查（不调用模型、不花钱）。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vectorStore", properties.getVectorStore().wireName());
        body.put("vectorTable", properties.getVectorStore() == VectorStoreType.PGVECTOR
                ? properties.getVectorTableName() : null);
        body.put("embeddingDimensions", properties.getEmbeddingDimensions());
        body.put("topK", properties.getTopK());
        body.put("chunkStrategy", properties.getChunkStrategy().wireName());
        body.put("memoryMaxMessages", memory.maxMessages());
        body.put("activeSessions", memory.sessionIds().size());
        return body;
    }
}
