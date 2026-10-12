package com.example.aikb.stream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.Map;

/**
 * 把 {@link StreamEvent} 流接到 Spring MVC 的 {@link SseEmitter} 上。
 *
 * <p>Spring MVC 里最稳的做法就是自己订阅 Reactor 流、把每个事件写进 {@code SseEmitter}：
 * 事件名、JSON 序列化、超时与客户端断开的清理都能自己控制。
 *
 * <p>注意一个 SSE 的固有约定：一旦开始推送，HTTP 状态码就已经是 200 了。
 * 所以「开始之后的失败」不能变成 500，只能用 {@code error} 事件通知前端；
 * 而「开始之前的参数错误」仍然走普通异常，由 {@code ApiExceptionHandler} 返回 400。
 */
@Slf4j
public final class SseStreams {

    private SseStreams() {
    }

    /** 订阅事件流并返回可写的 SSE 响应。 */
    public static SseEmitter toEmitter(Flux<StreamEvent> events, long timeoutMs) {
        SseEmitter emitter = timeoutMs > 0 ? new SseEmitter(timeoutMs) : new SseEmitter();
        Disposable subscription = events.subscribe(
                event -> send(emitter, event),
                error -> {
                    log.warn("SSE 事件流异常结束：{}", error.getMessage());
                    send(emitter, StreamEvent.error(error));
                    complete(emitter);
                },
                () -> complete(emitter));
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(() -> {
            log.info("SSE 连接超时，主动结束");
            subscription.dispose();
            complete(emitter);
        });
        emitter.onError(error -> subscription.dispose());
        return emitter;
    }

    private static void send(SseEmitter emitter, StreamEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.name())
                    .data(event.data() == null ? Map.of() : event.data()));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 事件发送失败（客户端可能已断开）：{}", e.getMessage());
        }
    }

    private static void complete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException e) {
            log.debug("SSE 连接已经结束：{}", e.getMessage());
        }
    }
}
