package com.example.aikb.stream;

import com.example.aikb.agent.ToolCallRecord;
import com.example.aikb.agent.plan.Plan;
import com.example.aikb.observability.ModelCallRecord;
import com.example.aikb.observability.TokenUsage;
import com.example.aikb.rag.SourceRef;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 流式接口的事件载荷。
 *
 * <p>三个入口的元信息与收尾信息字段不同，这里用一份「按需填字段」的结构统一起来：
 * 空的字段不会出现在 JSON 里（{@link JsonInclude.Include#NON_NULL}），前端也好判断。
 */
public final class StreamPayloads {

    private StreamPayloads() {
    }

    /** 逐段输出：{@code {"text":"..."}} */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delta(String text) {
    }

    /** 失败原因，截断到可读长度，避免把整段供应商报文丢给前端。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Error(String message) {

        private static final int MAX_LENGTH = 300;

        public static Error from(Throwable error) {
            return new Error(describe(error));
        }

        static String describe(Throwable error) {
            if (error == null) {
                return "未知错误";
            }
            String message = error.getMessage();
            String text = message == null || message.isBlank()
                    ? error.getClass().getSimpleName()
                    : error.getClass().getSimpleName() + ": " + message;
            return text.length() <= MAX_LENGTH ? text : text.substring(0, MAX_LENGTH) + "...";
        }
    }

    /**
     * 开头事件：让前端在第一帧就知道会话 ID（后续要带回去）、本轮模式、
     * 命中的来源（RAG）和任务计划（Agent）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Meta(String sessionId, String mode, String retrievalMode,
                       List<SourceRef> sources, Plan plan) {

        public static Meta chat(String sessionId) {
            return new Meta(sessionId, "chat", null, null, null);
        }

        public static Meta rag(String sessionId, String retrievalMode, List<SourceRef> sources) {
            return new Meta(sessionId, "rag", retrievalMode, sources, null);
        }

        public static Meta agent(String sessionId, Plan plan) {
            return new Meta(sessionId, "agent", null, null, plan);
        }
    }

    /**
     * 收尾事件：答案已经输出完，这里补上引用编号、工具调用明细与本轮用量。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Done(String sessionId, String mode, int turns, String retrievalMode,
                       List<Integer> citations, List<String> toolsUsed,
                       List<ToolCallRecord> toolCalls, Plan plan, boolean degraded,
                       TokenUsage usage, double estimatedCost, long durationMs) {

        public static Done chat(String sessionId, int turns, ModelCallRecord record) {
            return build(sessionId, "chat", turns, null, null, null, null, null, false, record);
        }

        public static Done rag(String sessionId, int turns, String retrievalMode,
                               List<Integer> citations, ModelCallRecord record) {
            return build(sessionId, "rag", turns, retrievalMode, citations, null, null, null, false, record);
        }

        public static Done agent(String sessionId, int turns, List<String> toolsUsed,
                                 List<ToolCallRecord> toolCalls, Plan plan, boolean degraded,
                                 ModelCallRecord record) {
            return build(sessionId, "agent", turns, null, null, toolsUsed, toolCalls, plan, degraded, record);
        }

        private static Done build(String sessionId, String mode, int turns, String retrievalMode,
                                  List<Integer> citations, List<String> toolsUsed,
                                  List<ToolCallRecord> toolCalls, Plan plan, boolean degraded,
                                  ModelCallRecord record) {
            return new Done(
                    sessionId,
                    mode,
                    turns,
                    retrievalMode,
                    citations,
                    toolsUsed,
                    toolCalls,
                    plan,
                    degraded,
                    record == null ? TokenUsage.EMPTY : record.usage(),
                    record == null ? 0.0 : record.estimatedCost(),
                    record == null ? 0L : record.durationMs());
        }
    }
}
