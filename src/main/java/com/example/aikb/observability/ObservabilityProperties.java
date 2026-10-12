package com.example.aikb.observability;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 可观测性与成本统计配置，前缀 {@code app.observability}。
 *
 * <p>这里的单价只是**示例默认值**，用来让成本统计有数字可看；
 * 实际上线要按你所用模型供应商的价目表覆盖，否则算出来的费用没有意义。
 */
@Data
@ConfigurationProperties(prefix = "app.observability")
public class ObservabilityProperties {

    /** 关掉后不再累计调用明细与汇总；每次请求自身的耗时/token 仍会随响应返回。 */
    private boolean enabled = true;

    /** 计价货币，仅用于展示。 */
    private String currency = "CNY";

    /** 每 100 万输入 token 的价格（示例值，请按供应商价目表覆盖）。 */
    private double promptPricePer1M = 2.0;

    /** 每 100 万输出 token 的价格（示例值，请按供应商价目表覆盖）。 */
    private double completionPricePer1M = 8.0;

    /** {@code /api/observability/usage} 默认返回的最近调用条数。 */
    private int recentCallLimit = 50;

    /** 进程内最多保留多少条调用明细，超出后丢弃最旧的。 */
    private int maxRecentCalls = 200;

    /** 按 token 用量估算一次调用的费用；单价为「每 100 万 token」。 */
    public double cost(TokenUsage usage) {
        if (usage == null) {
            return 0.0;
        }
        double cost = usage.promptTokens() / 1_000_000d * promptPricePer1M
                + usage.completionTokens() / 1_000_000d * completionPricePer1M;
        return round6(cost);
    }

    /** 最近调用列表的长度上限，不允许超过进程内的保留上限。 */
    public int effectiveRecentCallLimit() {
        return Math.max(1, Math.min(recentCallLimit, effectiveMaxRecentCalls()));
    }

    public int effectiveMaxRecentCalls() {
        return Math.max(1, maxRecentCalls);
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }
}
