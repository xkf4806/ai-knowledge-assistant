package com.example.aikb.stream;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * Spring AI 响应体的取文本工具。
 *
 * <p>流式响应里会出现「只有用量、没有内容」的分片（结果是空列表），
 * 直接取 {@code getResult()} 会踩空，这里统一做一次防御。
 */
public final class ChatResponses {

    private ChatResponses() {
    }

    /** 取响应的文本内容；没有内容的分片返回空串。 */
    public static String text(ChatResponse response) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return "";
        }
        Generation generation = response.getResults().get(0);
        if (generation == null || generation.getOutput() == null) {
            return "";
        }
        AssistantMessage message = generation.getOutput();
        String text = message.getText();
        return text == null ? "" : text;
    }
}
