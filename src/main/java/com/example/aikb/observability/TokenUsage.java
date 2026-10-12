package com.example.aikb.observability;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 一次模型调用的 token 用量。
 *
 * <p>阻塞式调用一次拿到；流式调用里用量通常只在最后一段返回，
 * 所以用 {@link #merge(TokenUsage)} 把多段响应里的用量合并成一份。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {

    public static final TokenUsage EMPTY = new TokenUsage(0, 0, 0);

    /** 供应商没返回 total 时用 prompt + completion 兜底。 */
    public static TokenUsage of(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        int prompt = promptTokens == null ? 0 : promptTokens;
        int completion = completionTokens == null ? 0 : completionTokens;
        int total = totalTokens == null || totalTokens <= 0 ? prompt + completion : totalTokens;
        return new TokenUsage(prompt, completion, total);
    }

    /** 合并两段用量：优先取「有值的一方」，流式分片因此能拼出完整用量。 */
    public TokenUsage merge(TokenUsage other) {
        if (other == null) {
            return this;
        }
        int prompt = other.promptTokens > 0 ? other.promptTokens : this.promptTokens;
        int completion = other.completionTokens > 0 ? other.completionTokens : this.completionTokens;
        int total = other.totalTokens > 0 ? other.totalTokens : this.totalTokens;
        if (total <= 0) {
            total = prompt + completion;
        }
        return new TokenUsage(prompt, completion, total);
    }

    public boolean isEmpty() {
        return promptTokens <= 0 && completionTokens <= 0;
    }
}
