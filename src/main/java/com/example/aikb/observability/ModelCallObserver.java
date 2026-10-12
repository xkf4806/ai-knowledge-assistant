package com.example.aikb.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模型调用观测与成本统计。
 *
 * <p>用法是一次调用配对：{@code startCall(source, sessionId)} 拿一个 {@link Call} 句柄，
 * 期间用 {@link Call#observe(ChatResponse)} 喂响应（流式就喂每一片），
 * 结束时 {@link #finish(Call)} 落一条记录，出错则 {@link #fail(Call, Throwable)}。
 *
 * <p>记录分成两块：一块是「这一次调用」的明细（随响应返回给前端），
 * 一块是「进程内累计」的汇总（{@code /api/observability/usage} 给前端画成本面板）。
 * 累计只放在内存里——这是演示与自测用的轻量实现，重启即清零；
 * 要长期保存，把 {@link #record} 里的落库换成一个 repository 即可。
 */
@Slf4j
@Service
public class ModelCallObserver {

    private final ObservabilityProperties properties;
    private final AtomicLong sequence = new AtomicLong();

    /** 以下累计状态都在 this 上同步：调用量不大，简单正确优先。 */
    private final Map<String, UsageBreakdown> bySource = new LinkedHashMap<>();
    private final Map<String, UsageBreakdown> byModel = new LinkedHashMap<>();
    private final Deque<ModelCallRecord> recentCalls = new ArrayDeque<>();
    private long calls;
    private long failedCalls;
    private long promptTokens;
    private long completionTokens;
    private long totalTokens;
    private long totalDurationMs;
    private double estimatedCost;

    public ModelCallObserver(ObservabilityProperties properties) {
        this.properties = properties;
    }

    /** 开始一次调用计时。source 用 chat / rag / agent 标识是哪个入口发起的。 */
    public Call startCall(String source, String sessionId) {
        return new Call(source == null || source.isBlank() ? "unknown" : source, sessionId);
    }

    /** 调用成功结束：落一条记录并返回它，调用方据此把本次用量写进响应。 */
    public ModelCallRecord finish(Call call) {
        return record(call, true, null);
    }

    /** 调用失败结束：同样留一条记录（token 为 0），失败率与错误原因都可见。 */
    public ModelCallRecord fail(Call call, Throwable error) {
        return record(call, false, describe(error));
    }

    public synchronized UsageSummary summary() {
        return new UsageSummary(
                properties.isEnabled(),
                properties.getCurrency(),
                properties.getPromptPricePer1M(),
                properties.getCompletionPricePer1M(),
                calls,
                failedCalls,
                promptTokens,
                completionTokens,
                totalTokens,
                estimatedCost,
                calls == 0 ? 0 : totalDurationMs / calls,
                Collections.unmodifiableMap(new LinkedHashMap<>(bySource)),
                Collections.unmodifiableMap(new LinkedHashMap<>(byModel)),
                recentCalls(properties.effectiveRecentCallLimit()));
    }

    public synchronized void reset() {
        bySource.clear();
        byModel.clear();
        recentCalls.clear();
        calls = 0;
        failedCalls = 0;
        promptTokens = 0;
        completionTokens = 0;
        totalTokens = 0;
        totalDurationMs = 0;
        estimatedCost = 0.0;
        log.info("已重置模型调用用量统计");
    }

    private ModelCallRecord record(Call call, boolean success, String error) {
        // 一次调用只记一条：失败兜底、重复收尾（例如流为空后又触发一次 onError）都不该重复计数。
        synchronized (call) {
            ModelCallRecord existing = call.recorded();
            if (existing != null) {
                return existing;
            }
            TokenUsage usage = call.usage();
            ModelCallRecord record = new ModelCallRecord(
                    "call-" + sequence.incrementAndGet(),
                    Instant.now(),
                    call.source,
                    call.sessionId,
                    call.modelName(),
                    success,
                    error,
                    usage.promptTokens(),
                    usage.completionTokens(),
                    usage.totalTokens(),
                    properties.cost(usage),
                    call.durationMs());
            call.markRecorded(record);

            if (!properties.isEnabled()) {
                return record;
            }

            int max = properties.effectiveMaxRecentCalls();
            synchronized (this) {
                calls++;
                if (!success) {
                    failedCalls++;
                }
                promptTokens += record.promptTokens();
                completionTokens += record.completionTokens();
                totalTokens += record.totalTokens();
                totalDurationMs += record.durationMs();
                estimatedCost = round6(estimatedCost + record.estimatedCost());
                bySource.merge(record.source(), UsageBreakdown.EMPTY.plus(record), UsageBreakdown::merge);
                byModel.merge(record.model(), UsageBreakdown.EMPTY.plus(record), UsageBreakdown::merge);
                recentCalls.addFirst(record);
                while (recentCalls.size() > max) {
                    recentCalls.removeLast();
                }
                if (!success) {
                    log.warn("模型调用失败：source={}，sessionId={}，耗时 {} ms，原因：{}",
                            record.source(), record.sessionId(), record.durationMs(), record.error());
                } else {
                    log.info("模型调用完成：source={}，model={}，tokens={}（prompt {} / completion {}），耗时 {} ms，约 {} {}",
                            record.source(), record.model(), record.totalTokens(), record.promptTokens(),
                            record.completionTokens(), record.durationMs(), record.estimatedCost(),
                            properties.getCurrency());
                }
            }
            return record;
        }
    }

    private List<ModelCallRecord> recentCalls(int limit) {
        return recentCalls.stream().limit(limit).toList();
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        String type = error.getClass().getSimpleName();
        String text = message == null || message.isBlank() ? type : type + ": " + message;
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }

    /**
     * 一次调用的句柄：记录开始时间，并累计响应里出现的模型名与 token 用量。
     * 阻塞式调用喂一个 {@link ChatResponse}，流式调用把每一片都喂进来。
     */
    public static final class Call {

        private final String source;
        private final String sessionId;
        private final long startNanos = System.nanoTime();
        private String model;
        private TokenUsage usage = TokenUsage.EMPTY;
        private ModelCallRecord recorded;

        private Call(String source, String sessionId) {
            this.source = source;
            this.sessionId = sessionId;
        }

        public String source() {
            return source;
        }

        public String sessionId() {
            return sessionId;
        }

        /** 从 Spring AI 的响应里取模型名与 token 用量；没有的字段保持原值。 */
        public void observe(ChatResponse response) {
            if (response == null) {
                return;
            }
            ChatResponseMetadata metadata = response.getMetadata();
            if (metadata == null) {
                return;
            }
            String observedModel = metadata.getModel();
            if (observedModel != null && !observedModel.isBlank()) {
                this.model = observedModel;
            }
            observe(metadata.getUsage());
        }

        public void observe(Usage usage) {
            if (usage == null) {
                return;
            }
            this.usage = this.usage.merge(TokenUsage.of(
                    usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens()));
        }

        public TokenUsage usage() {
            return usage;
        }

        public String modelName() {
            return model == null || model.isBlank() ? "unknown" : model;
        }

        public long durationMs() {
            return (System.nanoTime() - startNanos) / 1_000_000L;
        }

        ModelCallRecord recorded() {
            return recorded;
        }

        void markRecorded(ModelCallRecord record) {
            this.recorded = record;
        }
    }
}
