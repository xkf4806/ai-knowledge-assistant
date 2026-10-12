package com.example.aikb.stream;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 流式输出的静默兜底：一直没内容时要变成一条可读错误，而不是无限等。
 */
class StreamTimeoutsTest {

    @Test
    void passesEventsThroughWhenTheyArriveInTime() {
        List<StreamEvent> events = StreamTimeouts
                .guardIdle(Flux.just(StreamEvent.delta("a"), StreamEvent.delta("b")), 5000)
                .collectList()
                .block();

        assertThat(events).hasSize(2);
    }

    @Test
    void failsWithReadableMessageWhenNothingArrives() {
        assertThatThrownBy(() -> StreamTimeouts.guardIdle(Flux.never(), 1500).blockLast())
                .isInstanceOf(ModelStreamTimeoutException.class)
                .hasMessageContaining("没有返回任何内容")
                .hasMessageContaining("可以稍后重试");
    }

    @Test
    void isIdleTimeoutNotTotalTimeout() {
        // 每 200ms 来一段，总量超过 1.5 秒也不算超时——只有「静默」才触发
        List<StreamEvent> events = StreamTimeouts
                .guardIdle(Flux.interval(Duration.ofMillis(200))
                        .take(3)
                        .map(i -> StreamEvent.delta("chunk-" + i)), 1500)
                .collectList()
                .block();

        assertThat(events).hasSize(3);
    }

    @Test
    void canBeDisabled() {
        List<StreamEvent> events = StreamTimeouts.guardIdle(Flux.just(StreamEvent.delta("a")), 0)
                .collectList()
                .block();

        assertThat(events).hasSize(1);
    }
}
