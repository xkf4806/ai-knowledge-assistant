package com.example.aikb.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 一次工具调用的观测记录。
 *
 * <p>模型决定调用工具这件事本身对调用方是不可见的：Spring AI 在内部循环里执行完工具、
 * 把结果回灌给模型，最后只把「最终答复」交给我们。为了让 Agent 的行为可解释、可演示、可测试，
 * 这里把每次调用（工具名、参数、返回、耗时、状态、尝试次数、错误）都记下来，随响应一起返回。
 *
 * @param attempts 这一次调用共尝试了几次：1 表示一次成功，>1 表示重试过
 * @param error    重试耗尽后的最后一次错误；成功或拦截时为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolCallRecord(
        String name,
        String arguments,
        String result,
        Status status,
        long durationMs,
        int attempts,
        String error) {

    public enum Status {
        /** 工具正常执行并返回结果（可能经历过重试） */
        OK,
        /** 重试耗尽后仍失败，已把兜底提示回灌给模型 */
        ERROR,
        /** 重试耗尽后仍超时，已把兜底提示回灌给模型 */
        TIMEOUT,
        /** 本轮工具调用次数达到上限，直接拦截 */
        BLOCKED
    }

    public boolean succeeded() {
        return status == Status.OK;
    }

    /** 是否靠重试才成功。 */
    public boolean retried() {
        return attempts > 1;
    }

    /** 是否发生了失败兜底：请求没有「硬失败」，但这一步的结果降级了。 */
    public boolean degraded() {
        return status == Status.ERROR || status == Status.TIMEOUT;
    }

    static ToolCallRecord ok(String name, String arguments, String result, long durationMs, int attempts) {
        return new ToolCallRecord(name, arguments, result, Status.OK, durationMs, attempts, null);
    }

    static ToolCallRecord error(String name, String arguments, String result,
                                long durationMs, int attempts, String error) {
        return new ToolCallRecord(name, arguments, result, Status.ERROR, durationMs, attempts, error);
    }

    static ToolCallRecord timeout(String name, String arguments, String result,
                                  long durationMs, int attempts, String error) {
        return new ToolCallRecord(name, arguments, result, Status.TIMEOUT, durationMs, attempts, error);
    }

    static ToolCallRecord blocked(String name, String arguments, String result) {
        return new ToolCallRecord(name, arguments, result, Status.BLOCKED, 0L, 0, "本轮工具调用次数已达上限");
    }
}
