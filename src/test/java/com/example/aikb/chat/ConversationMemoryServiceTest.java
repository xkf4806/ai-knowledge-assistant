package com.example.aikb.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationMemoryServiceTest {

    @Test
    void generatesSessionIdWhenAbsentAndKeepsGivenOne() {
        ConversationMemoryService memory = newService(10);

        String generated = memory.resolveSessionId(null);
        assertThat(generated).isNotBlank();
        assertThat(memory.resolveSessionId("  session-1  ")).isEqualTo("session-1");
    }

    @Test
    void keepsHistoryWithinConfiguredWindow() {
        ConversationMemoryService memory = newService(4);
        String sessionId = "s1";

        for (int i = 1; i <= 3; i++) {
            memory.record(sessionId, "问题" + i, "回答" + i);
        }

        // 窗口为 4 条消息（2 轮），最早的一轮应被丢弃
        assertThat(memory.history(sessionId)).hasSize(4);
        assertThat(memory.turnCount(sessionId)).isEqualTo(2);
        assertThat(memory.history(sessionId))
                .extracting(message -> message.getMessageType())
                .containsExactly(MessageType.USER, MessageType.ASSISTANT, MessageType.USER, MessageType.ASSISTANT);
        assertThat(memory.history(sessionId).get(0).getText()).isEqualTo("问题2");
    }

    @Test
    void clearRemovesSession() {
        ConversationMemoryService memory = newService(10);
        memory.record("s1", "问题", "回答");

        memory.clear("s1");

        assertThat(memory.history("s1")).isEmpty();
        assertThat(memory.sessionIds()).doesNotContain("s1");
    }

    private static ConversationMemoryService newService(int maxMessages) {
        ChatMemoryProperties properties = new ChatMemoryProperties();
        properties.setMaxMessages(maxMessages);
        return new ConversationMemoryService(properties);
    }
}
