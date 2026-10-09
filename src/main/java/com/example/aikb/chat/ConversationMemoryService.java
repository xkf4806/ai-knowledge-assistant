package com.example.aikb.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多轮对话记忆（第 3 周学习重点）。
 *
 * <p>底层直接用 Spring AI 的 {@link MessageWindowChatMemory}：它按会话 ID 维护消息列表，
 * 并且只保留最近 {@code max-messages} 条，天然做了「滑动窗口」控制，避免历史无限增长把 token 撑爆。
 * 这里再包一层，负责会话 ID 生成、一问一答成对写入，以及统计当前会话轮数。
 */
@Slf4j
@Service
public class ConversationMemoryService {

    private final ChatMemory chatMemory;
    private final int maxMessages;

    /** 记录出现过的会话 ID，仅用于统计/观测，不影响记忆本身。 */
    private final Set<String> knownSessions = ConcurrentHashMap.newKeySet();

    public ConversationMemoryService(ChatMemoryProperties properties) {
        // 窗口至少要能装下一问一答两条消息。
        this.maxMessages = Math.max(properties.getMaxMessages(), 2);
        this.chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(this.maxMessages)
                .build();
    }

    /** 调用方没带 sessionId 时生成一个新的，带了就用调用方的。 */
    public String resolveSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return sessionId.trim();
    }

    /** 取出会话历史（按时间顺序排列的 user / assistant 消息）。 */
    public List<Message> history(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        return chatMemory.get(sessionId);
    }

    /** 追加一轮问答，供下一次提问带上上下文。 */
    public void record(String sessionId, String question, String answer) {
        chatMemory.add(sessionId, List.of(new UserMessage(question), new AssistantMessage(answer)));
        knownSessions.add(sessionId);
    }

    /** 清空某个会话的历史。 */
    public void clear(String sessionId) {
        chatMemory.clear(sessionId);
        boolean removed = knownSessions.remove(sessionId);
        if (removed) {
            log.info("已清空会话 {} 的记忆", sessionId);
        }
    }

    /** 已记录的历史轮数（一问一答算一轮），用于返回给前端展示。 */
    public int turnCount(String sessionId) {
        return history(sessionId).size() / 2;
    }

    public Set<String> sessionIds() {
        return Set.copyOf(knownSessions);
    }

    public int maxMessages() {
        return maxMessages;
    }
}
