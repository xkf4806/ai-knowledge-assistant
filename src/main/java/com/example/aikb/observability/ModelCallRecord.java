package com.example.aikb.observability;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 一次模型调用的观测记录：谁调的、用的哪个模型、花了多少 token 与时间、失败了没有。
 *
 * <p>以前，「调用追踪」只覆盖工具调用；模型调用本身是黑盒。
 * 这一层把模型调用也记下来，前端才能展示「这一轮花了多少 token / 多少钱」。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelCallRecord(
        String id,
        Instant time,
        String source,
        String sessionId,
        String model,
        boolean success,
        String error,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        double estimatedCost,
        long durationMs) {

    @JsonIgnore
    public TokenUsage usage() {
        return new TokenUsage(promptTokens, completionTokens, totalTokens);
    }
}
