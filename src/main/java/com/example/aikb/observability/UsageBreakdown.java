package com.example.aikb.observability;

/**
 * 某个维度（调用来源 / 模型）的用量小计。
 */
public record UsageBreakdown(
        long calls,
        long failedCalls,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        double estimatedCost,
        long totalDurationMs) {

    public static final UsageBreakdown EMPTY = new UsageBreakdown(0, 0, 0, 0, 0, 0.0, 0);

    public UsageBreakdown plus(ModelCallRecord record) {
        return new UsageBreakdown(
                calls + 1,
                failedCalls + (record.success() ? 0 : 1),
                promptTokens + record.promptTokens(),
                completionTokens + record.completionTokens(),
                totalTokens + record.totalTokens(),
                round6(estimatedCost + record.estimatedCost()),
                totalDurationMs + record.durationMs());
    }

    public UsageBreakdown merge(UsageBreakdown other) {
        if (other == null) {
            return this;
        }
        return new UsageBreakdown(
                calls + other.calls,
                failedCalls + other.failedCalls,
                promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                totalTokens + other.totalTokens,
                round6(estimatedCost + other.estimatedCost),
                totalDurationMs + other.totalDurationMs);
    }

    /** 平均耗时，前端直接拿来展示，不用自己除。 */
    public long avgDurationMs() {
        return calls == 0 ? 0 : totalDurationMs / calls;
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }
}
