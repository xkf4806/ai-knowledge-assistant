package com.example.aikb.rag;

import com.example.aikb.rag.splitter.ChunkingOptions;
import com.example.aikb.rag.splitter.ChunkingStrategy;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private static final List<ChunkingStrategy> ALL_STRATEGIES =
            List.of(ChunkingStrategy.FIXED, ChunkingStrategy.HEADING, ChunkingStrategy.PARAGRAPH);

    private final RagService ragService;
    private final IngestionService ingestionService;
    private final RagProperties properties;

    public RagController(RagService ragService, IngestionService ingestionService, RagProperties properties) {
        this.ragService = ragService;
        this.ingestionService = ingestionService;
        this.properties = properties;
    }

    /**
     * 把 classpath:sample-docs/* 导入内存向量库。
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

    /** 基于知识库提问 */
    @PostMapping("/ask")
    public RagAnswer ask(@RequestBody AskRequest request) {
        return ragService.ask(request.getQuestion());
    }
}
