package com.example.aikb.stream;

/**
 * 模型既没有流式返回内容、非流式重试也拿不到内容时抛出。
 *
 * <p>实测某些 OpenAI 兼容网关（硅基流动的流式接口）会返回「消耗了 token 但 content 为空」的响应：
 * {@code delta.content=""} 之后直接 {@code finish_reason=stop}，一个字的正文都没有。
 * 这类响应不能当成「模型答了但没内容」糊弄过去，要明确告诉用户。
 */
public class EmptyModelResponseException extends RuntimeException {

    public EmptyModelResponseException() {
        super("模型这次没有返回任何内容：网关的流式输出是空的，改用非流式重试也没有拿到内容。"
                + "这通常是网关侧的临时问题，稍后再问一次通常就好了。");
    }
}
