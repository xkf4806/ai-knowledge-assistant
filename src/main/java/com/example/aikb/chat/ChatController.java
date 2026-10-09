package com.example.aikb.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 最基础的对话接口，不接知识库，用来先验证模型连通性。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String answer = chatClient.prompt()
                .user(request.getMessage())
                .call()
                .content();
        return new ChatResponse(answer);
    }
}
