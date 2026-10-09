package com.example.aikb.rag.splitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 分块策略（第 2 周学习重点）：
 * <ul>
 *   <li>{@code FIXED}：按固定字符数切分，相邻片段保留重叠窗口（第 1 周的朴素策略）。</li>
 *   <li>{@code HEADING}：先按 Markdown / 文本标题切成小节，超长小节再按固定长度切分。</li>
 *   <li>{@code PARAGRAPH}：按空行分段，把相邻小段落合并到目标长度。</li>
 * </ul>
 */
public enum ChunkingStrategy {
    FIXED,
    HEADING,
    PARAGRAPH;

    /** 解析单个策略名，大小写不敏感；非法值给出可选值提示。 */
    public static ChunkingStrategy from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("分块策略不能为空，可选值：fixed / heading / paragraph");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "不支持的分块策略：" + value + "，可选值：fixed / heading / paragraph");
        }
    }

    /** 解析逗号分隔的策略列表，用于一次对比多种策略，例如 {@code fixed,heading,paragraph}。 */
    public static List<ChunkingStrategy> parseList(String csv) {
        List<ChunkingStrategy> strategies = new ArrayList<>();
        for (String part : csv.split(",")) {
            if (!part.isBlank()) {
                strategies.add(from(part));
            }
        }
        if (strategies.isEmpty()) {
            throw new IllegalArgumentException("分块策略列表不能为空");
        }
        return strategies;
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
