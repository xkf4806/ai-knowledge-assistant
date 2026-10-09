package com.example.aikb.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基础对话接口，不接知识库：用来验证模型连通性，以及单独演示「多轮对话记忆」。
 *
 * <p>第 3 周起支持 {@code sessionId}：不传时后端生成并返回，下次带上同一个 ID 就能记住上下文。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient chatClient;
    private final ConversationMemoryService memory;

    public ChatController(ChatClient.Builder chatClientBuilder, ConversationMemoryService memory) {
        this.chatClient = chatClientBuilder.build();
        this.memory = memory;
    }

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        if (request == null || request.getMessage() == null || request.getMessage().isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        String sessionId = memory.resolveSessionId(request.getSessionId());
        List<Message> history = memory.history(sessionId);

        String answer = chatClient.prompt()
                .messages(history)
                .user(request.getMessage())
                .call()
                .content();

        memory.record(sessionId, request.getMessage(), answer);
        return new ChatResponse(sessionId, answer, memory.turnCount(sessionId));
    }

    /** 清空某个会话的上下文记忆，方便演示「同一问题、有无历史」的差异。 */
    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, Object> clearSession(@PathVariable String sessionId) {
        memory.clear(sessionId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("cleared", true);
        return body;
    }
}
