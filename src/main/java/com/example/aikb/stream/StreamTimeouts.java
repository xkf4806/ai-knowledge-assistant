package com.example.aikb.stream;

import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * 流式输出的超时保护。
 *
 * <p>「一直没反应」比「明确报错」难排查得多：用户只看到一个闪烁的光标。
 * 这里给输出流加一道静默时限——超过时限没有新的 {@code delta}（包括第一个字），
 * 就抛 {@link ModelStreamTimeoutException}，由上层转成一条 {@code error} 事件。
 */
public final class StreamTimeouts {

    private StreamTimeouts() {
    }

    /** 给输出流加静默时限；{@code idleTimeoutMs <= 0} 表示不检查。 */
    public static Flux<StreamEvent> guardIdle(Flux<StreamEvent> deltas, long idleTimeoutMs) {
        if (idleTimeoutMs <= 0) {
            return deltas;
        }
        return deltas.timeout(Duration.ofMillis(idleTimeoutMs),
                Flux.error(new ModelStreamTimeoutException(idleTimeoutMs)));
    }
}
