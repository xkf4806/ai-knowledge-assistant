package com.example.aikb.chat;

import com.example.aikb.stream.SseStreams;
import com.example.aikb.stream.StreamProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基础对话接口，不接知识库：用来验证模型连通性，以及单独演示「多轮对话记忆」。
 *
 * <p>支持 {@code sessionId}：不传时后端生成并返回，下次带上同一个 ID 就能记住上下文。
 * 加了 {@code /stream}：同样的对话，用 SSE 一段段吐字。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final ConversationMemoryService memory;
    private final StreamProperties streamProperties;

    public ChatController(ChatService chatService,
                          ConversationMemoryService memory,
                          StreamProperties streamProperties) {
        this.chatService = chatService;
        this.memory = memory;
        this.streamProperties = streamProperties;
    }

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        requireMessage(request);
        return chatService.chat(request);
    }

    /** 流式对话：{@code text/event-stream}，事件协议见 {@code com.example.aikb.stream.StreamEvent}。 */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        requireMessage(request);
        return SseStreams.toEmitter(chatService.stream(request), streamProperties.getTimeoutMs());
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

    private static void requireMessage(ChatRequest request) {
        if (request == null || request.getMessage() == null || request.getMessage().isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
    }
}
