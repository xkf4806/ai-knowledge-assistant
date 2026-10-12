package com.example.aikb.stream;

/**
 * SSE 事件：一个事件名 + 一个可序列化的载荷。
 *
 * <p>三种流式接口（chat / rag / agent）共用同一套事件协议，前端只需要写一份解析逻辑：
 * <ul>
 *   <li>{@code meta}：本轮的基本信息（会话 ID、模式、检索模式、命中的来源、任务计划）</li>
 *   <li>{@code delta}：模型输出的一小段文本，前端逐段追加即可得到打字机效果</li>
 *   <li>{@code done}：收尾信息（引用编号、工具调用明细、token 用量、估算费用、耗时）</li>
 *   <li>{@code error}：本轮失败的可读原因（连接已经在 200 上建立，所以错误也用事件下发）</li>
 * </ul>
 */
public record StreamEvent(String name, Object data) {

    public static StreamEvent meta(Object data) {
        return new StreamEvent("meta", data);
    }

    public static StreamEvent delta(String text) {
        return new StreamEvent("delta", new StreamPayloads.Delta(text));
    }

    public static StreamEvent done(Object data) {
        return new StreamEvent("done", data);
    }

    public static StreamEvent error(Throwable error) {
        return new StreamEvent("error", StreamPayloads.Error.from(error));
    }
}
