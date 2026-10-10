package com.example.aikb.rag;

import com.example.aikb.chat.ConversationMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 问答编排：多轮记忆 + 向量检索 + 拼装带编号的上下文 + 生成答案 + 引用校验。
 *
 * <p>第 3 周在原来「检索 → 生成」的基础上补了三件事：
 * <ul>
 *   <li>检索交给 {@link RetrievalService}，命中的片段带来源、分数与「第几段」；</li>
 *   <li>上下文里给每段资料编号 {@code [1]、[2]}，提示模型在答案里标注引用；</li>
 *   <li>把每一轮问答写进 {@link ConversationMemoryService}，下次提问自动带上历史。</li>
 * </ul>
 */
@Slf4j
@Service
public class RagService {

    /** 系统提示词放在资源文件里，显式按 UTF-8 读取，避免依赖源码编码与文本块语法。 */
    private static final String SYSTEM_PROMPT_RESOURCE = "prompts/system-prompt.txt";

    /** 检索不到资料时的兜底回答，避免模型自由发挥。 */
    private static final String NO_ANSWER = "根据现有资料无法回答该问题。";

    /** 匹配答案里的引用编号，如 [1]、[12] */
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");

    private final ChatClient chatClient;
    private final RetrievalService retrievalService;
    private final ConversationMemoryService memory;
    private final String systemPrompt;

    public RagService(ChatClient.Builder chatClientBuilder,
                      RetrievalService retrievalService,
                      ConversationMemoryService memory) {
        this.chatClient = chatClientBuilder.build();
        this.retrievalService = retrievalService;
        this.memory = memory;
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

    public RagAnswer ask(AskRequest request) {
        String question = request.getQuestion();
        String sessionId = memory.resolveSessionId(request.getSessionId());
        RetrievalMode retrievalMode = request.getRetrievalMode() == null
                || request.getRetrievalMode().isBlank()
                ? null
                : RetrievalMode.from(request.getRetrievalMode());
        List<Message> history = memory.history(sessionId);

        List<RetrievedChunk> hits = retrievalService.retrieve(question, request.getSource(), retrievalMode);
        if (hits.isEmpty()) {
            memory.record(sessionId, question, NO_ANSWER);
            return new RagAnswer(sessionId, NO_ANSWER, List.of(), List.of(),
                    memory.turnCount(sessionId), retrievalModeName(retrievalMode));
        }

        String prompt = buildPrompt(question, hits);
        String answer = chatClient.prompt()
                .system(systemPrompt)
                .messages(history)
                .user(prompt)
                .call()
                .content();

        List<Integer> citations = extractCitations(answer, hits.size());
        List<SourceRef> sources = hits.stream().map(RetrievedChunk::sourceRef).toList();

        memory.record(sessionId, question, answer);
        return new RagAnswer(sessionId, answer, sources, citations,
                memory.turnCount(sessionId), retrievalModeName(retrievalMode));
    }

    private String retrievalModeName(RetrievalMode retrievalMode) {
        return retrievalMode == null
                ? "default"
                : retrievalMode.wireName();
    }

    /** 把命中片段拼成带编号与来源的参考资料，让模型既能答对、也能标注引用。 */
    private static String buildPrompt(String question, List<RetrievedChunk> hits) {
        StringBuilder prompt = new StringBuilder("【参考资料】\n");
        for (RetrievedChunk hit : hits) {
            SourceRef source = hit.sourceRef();
            prompt.append(hit.label());
            if (source != null && source.getLocation() != null) {
                prompt.append("（来源：").append(source.getLocation()).append("）");
            }
            prompt.append("\n").append(hit.text()).append("\n\n");
        }
        prompt.append("【问题】\n").append(question).append("\n\n")
              .append("请只依据上面的参考资料回答，并在句末用 [1]、[2] 这样的编号标注引用的资料。")
              .append("如果资料里没有答案，直接回答「").append(NO_ANSWER).append("」。");
        return prompt.toString();
    }

    /**
     * 解析答案里的引用编号并校验范围：只保留真实存在的编号，
     * 越界编号说明模型编造了来源，记一条告警日志，方便第 4 周做幻觉监测。
     */
    static List<Integer> extractCitations(String answer, int maxIndex) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        TreeSet<Integer> valid = new TreeSet<>();
        List<Integer> invalid = new ArrayList<>();
        Matcher matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (index >= 1 && index <= maxIndex) {
                valid.add(index);
            } else {
                invalid.add(index);
            }
        }
        if (!invalid.isEmpty()) {
            log.warn("答案出现了不存在的引用编号 {}（有效范围 1-{}），疑似幻觉引用", invalid, maxIndex);
        }
        return List.copyOf(valid);
    }
}
