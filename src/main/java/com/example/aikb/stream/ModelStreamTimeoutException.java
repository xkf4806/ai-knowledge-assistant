package com.example.aikb.stream;

/**
 * 流式输出长时间没有新内容时抛出。
 *
 * <p>消息是给用户看的：说明「等了多久」「可能是什么原因」「还能做什么」。
 */
public class ModelStreamTimeoutException extends RuntimeException {

    public ModelStreamTimeoutException(long idleTimeoutMs) {
        super("模型在 " + Math.max(1, idleTimeoutMs / 1000) + " 秒内没有返回任何内容，已中止本轮请求。"
                + "常见原因：到模型网关的网络不通或超时、被限流、或模型排队过久。"
                + "可以稍后重试；如果「用量」面板里连一条调用记录都没有，基本就是没连上网关。");
    }
}
