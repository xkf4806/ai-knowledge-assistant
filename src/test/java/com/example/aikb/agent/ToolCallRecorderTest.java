package com.example.aikb.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallRecorderTest {

    @Test
    void acquiresUpToLimitThenBlocks() {
        AgentProperties properties = new AgentProperties();
        properties.setMaxToolCalls(2);
        ToolCallRecorder recorder = new ToolCallRecorder(properties);

        recorder.begin();
        assertThat(recorder.tryAcquire()).isTrue();
        assertThat(recorder.tryAcquire()).isTrue();
        assertThat(recorder.tryAcquire()).isFalse();
        recorder.end();
    }

    @Test
    void endReturnsCollectedRecordsAndClearsSession() {
        ToolCallRecorder recorder = new ToolCallRecorder(new AgentProperties());

        recorder.begin();
        recorder.record(ToolCallRecord.ok("lookupOrder", "{\"orderId\":\"A1001\"}", "{}", 3L, 1));
        List<ToolCallRecord> records = recorder.end();

        assertThat(records).hasSize(1);
        assertThat(records.get(0).name()).isEqualTo("lookupOrder");
        assertThat(records.get(0).attempts()).isEqualTo(1);
        assertThat(records.get(0).retried()).isFalse();
        // 会话已清理：没有 begin() 的线程不会误记、也不会拿到额度
        assertThat(recorder.end()).isEmpty();
        assertThat(recorder.tryAcquire()).isFalse();
    }
}
