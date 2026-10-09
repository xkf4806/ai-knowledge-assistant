package com.example.aikb.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 最小 RAG 链路：检索（向量相似度） -> 拼装上下文 -> 交给大模型生成答案。
 * 这里手写检索与拼装，是为了让第 1 周看清 RAG 的每一步。
 */
@Service
public class RagService {

    /** 系统提示词放在资源文件里，显式按 UTF-8 读取，避免依赖源码编码与文本块语法。 */
    private static final String SYSTEM_PROMPT_RESOURCE = "prompts/system-prompt.txt";

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final RagProperties properties;
    private final String systemPrompt;

    public RagService(ChatClient.Builder chatClientBuilder, VectorStore vectorStore, RagProperties properties) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.systemPrompt = loadSystemPrompt();
    }

    private static String loadSystemPrompt() {
        try {
            return new ClassPathResource(SYSTEM_PROMPT_RESOURCE)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("加载系统提示词失败: " + SYSTEM_PROMPT_RESOURCE, e);
        }
    }

    public RagAnswer ask(String question) {
        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder().query(question).topK(properties.getTopK()).build());

        if (hits == null || hits.isEmpty()) {
            return new RagAnswer("根据现有资料无法回答该问题。", Collections.emptyList());
        }

        StringBuilder context = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            context.append("[").append(i + 1).append("] ")
                   .append(hits.get(i).getText())
                   .append("\n");
        }

        String prompt = "【参考资料】\n" + context + "\n【问题】\n" + question;
        String answer = chatClient.prompt()
                .system(systemPrompt)
                .user(prompt)
                .call()
                .content();

        List<SourceRef> sources = hits.stream()
                .map(d -> new SourceRef(
                        String.valueOf(d.getMetadata().get("source")),
                        String.valueOf(d.getMetadata().get("chunk")),
                        d.getMetadata().get("heading") == null
                                ? null
                                : String.valueOf(d.getMetadata().get("heading"))))
                .collect(Collectors.toList());

        return new RagAnswer(answer, sources);
    }
}
