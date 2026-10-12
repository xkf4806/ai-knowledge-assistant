package com.example.aikb.observability;

import java.util.List;
import java.util.Map;

/**
 * 进程内用量汇总：总览 + 按来源/模型分解 + 最近调用明细。
 *
 * @param enabled 是否开启累计
 * @param currency 计价货币
 * @param promptPricePer1M / completionPricePer1M 当前使用的单价，方便前端显示「按什么算的」
 */
public record UsageSummary(
        boolean enabled,
        String currency,
        double promptPricePer1M,
        double completionPricePer1M,
        long calls,
        long failedCalls,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        double estimatedCost,
        long avgDurationMs,
        Map<String, UsageBreakdown> bySource,
        Map<String, UsageBreakdown> byModel,
        List<ModelCallRecord> recentCalls) {
}
