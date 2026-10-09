package com.example.aikb.rag;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;
    private final IngestionService ingestionService;

    public RagController(RagService ragService, IngestionService ingestionService) {
        this.ragService = ragService;
        this.ingestionService = ingestionService;
    }

    /** 把 classpath:sample-docs/*.md 导入内存向量库 */
    @PostMapping("/ingest")
    public IngestResponse ingest() {
        return new IngestResponse(ingestionService.ingestSampleDocs());
    }

    /** 基于知识库提问 */
    @PostMapping("/ask")
    public RagAnswer ask(@RequestBody AskRequest request) {
        return ragService.ask(request.getQuestion());
    }
}
