package com.example.aikb.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.concurrent.TimeUnit;

/**
 * 工具回调装饰器：在不改动工具代码的前提下，
 * 包住 Spring AI 生成的 {@link ToolCallback}，记录「谁被调用、参数是什么、返回了什么、花了多久」。
 *
 * <p>它多了一件重要的事：<b>失败不再直接抛给上层</b>。超时或重试耗尽后，它把一段
 * 结构化的兜底提示（{@code {"error":...,"fallback":true}}）当作工具结果回灌给模型，
 * 于是模型能就着已有信息作答，或者如实告诉用户「这条数据暂时查不到」，而不是让整轮请求 500。
 * 这一次调用会以 {@code ERROR} / {@code TIMEOUT} 状态出现在 {@code toolCalls} 里，过程依旧可观测。
 */
@Slf4j
class RecordingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolCallRecorder recorder;
    private final ToolExecutionPolicy policy;

    RecordingToolCallback(ToolCallback delegate, ToolCallRecorder recorder, ToolExecutionPolicy policy) {
        this.delegate = delegate;
        this.recorder = recorder;
        this.policy = policy;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = delegate.getToolDefinition().name();
        if (!recorder.tryAcquire(toolContext)) {
            String blocked = "{\"error\":\"本轮工具调用次数已达上限（"
                    + recorder.maxToolCalls() + " 次），请基于已有信息作答。\"}";
            recorder.record(ToolCallRecord.blocked(name, toolInput, blocked), toolContext);
            return blocked;
        }

        long start = System.nanoTime();
        ToolExecutionPolicy.Result outcome = policy.execute(name, () -> delegate.call(toolInput, toolContext));
        long durationMs = elapsedMs(start);

        if (outcome.success()) {
            recorder.record(ToolCallRecord.ok(name, toolInput, outcome.value(), durationMs, outcome.attempts()),
                    toolContext);
            return outcome.value();
        }

        String fallback = fallbackPayload(name, outcome);
        if (outcome.timedOut()) {
            recorder.record(ToolCallRecord.timeout(name, toolInput, fallback,
                    durationMs, outcome.attempts(), outcome.error()), toolContext);
        } else {
            recorder.record(ToolCallRecord.error(name, toolInput, fallback,
                    durationMs, outcome.attempts(), outcome.error()), toolContext);
        }
        log.warn("工具 {} 执行失败，已返回兜底提示：attempts={}，error={}",
                name, outcome.attempts(), outcome.error());
        return fallback;
    }

    private static String fallbackPayload(String name, ToolExecutionPolicy.Result outcome) {
        String reason = outcome.timedOut() ? "执行超时" : "执行失败";
        return "{\"error\":\"工具 " + name + " " + reason + "（已尝试 " + outcome.attempts() + " 次："
                + escape(outcome.error()) + "）。请基于已获取的信息作答；"
                + "若该数据是结论所必需，请如实告知用户暂时无法获取，不要编造。\",\"fallback\":true}";
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("\\", "/").replace("\"", "'");
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
