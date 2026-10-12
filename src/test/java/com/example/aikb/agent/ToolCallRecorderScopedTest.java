package com.example.aikb.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式路径下的工具调用收集：会话 ID 通过 {@link ToolContext} 传递，可以在别的线程上收尾。
 */
class ToolCallRecorderScopedTest {

    @Test
    void collectsCallsThroughToolContextAcrossThreads() throws Exception {
        ToolCallRecorder recorder = new ToolCallRecorder(new AgentProperties());
        ToolCallRecorder.Scope scope = recorder.beginScoped();
        ToolContext context = new ToolContext(recorder.contextFor(scope));

        assertThat(recorder.tryAcquire(context)).isTrue();
        recorder.record(ToolCallRecord.ok("lookupOrder", "{\"orderId\":\"A1001\"}", "{}", 3L, 1), context);

        // 模拟「在另一个线程上收尾」：不同线程也能按 ID 取回同一轮记录
        List<ToolCallRecord> records = new ArrayList<>();
        Thread worker = new Thread(() -> records.addAll(recorder.endScoped(scope)));
        worker.start();
        worker.join();

        assertThat(records).hasSize(1);
        assertThat(records.get(0).name()).isEqualTo("lookupOrder");
        // 收尾后会话已清理，重复收尾不会重复计数
        assertThat(recorder.endScoped(scope)).isEmpty();
    }

    @Test
    void respectsCallLimitWithinScopedSession() {
        AgentProperties properties = new AgentProperties();
        properties.setMaxToolCalls(2);
        ToolCallRecorder recorder = new ToolCallRecorder(properties);
        ToolContext context = new ToolContext(recorder.contextFor(recorder.beginScoped()));

        assertThat(recorder.tryAcquire(context)).isTrue();
        assertThat(recorder.tryAcquire(context)).isTrue();
        assertThat(recorder.tryAcquire(context)).isFalse();
    }

    @Test
    void allowsButDoesNotRecordWhenNoSessionCanBeResolved() {
        // 上下文没传到位时宁可少一条观测数据，也不能把工具挡掉
        ToolCallRecorder recorder = new ToolCallRecorder(new AgentProperties());
        ToolContext unknown = new ToolContext(Map.of());

        assertThat(recorder.tryAcquire(unknown)).isTrue();
        recorder.record(ToolCallRecord.ok("echo", "{}", "{}", 1L, 1), unknown);
        assertThat(recorder.end()).isEmpty();
    }
}
