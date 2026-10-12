package com.example.aikb.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecordingToolCallbackTest {

    @Test
    void recordsSuccessfulInvocation() {
        AgentProperties properties = new AgentProperties();
        ToolCallRecorder recorder = new ToolCallRecorder(properties);
        ToolCallback callback = wrap(new SampleTools(), "echo", recorder, TestPolicies.of(properties));

        recorder.begin();
        String result = callback.call("{\"value\":\"A1001\"}");
        List<ToolCallRecord> records = recorder.end();

        assertThat(result).contains("echo:A1001");
        assertThat(records).hasSize(1);
        ToolCallRecord record = records.get(0);
        assertThat(record.name()).isEqualTo("echo");
        assertThat(record.arguments()).contains("A1001");
        assertThat(record.status()).isEqualTo(ToolCallRecord.Status.OK);
        assertThat(record.succeeded()).isTrue();
        assertThat(record.attempts()).isEqualTo(1);
        assertThat(record.retried()).isFalse();
    }

    @Test
    void retriesTransientFailuresAndRecordsAttempts() {
        AgentProperties properties = new AgentProperties();
        ToolCallRecorder recorder = new ToolCallRecorder(properties);
        SampleTools tools = new SampleTools();
        ToolCallback callback = wrap(tools, "flaky", recorder, TestPolicies.of(properties));

        recorder.begin();
        String result = callback.call("{\"value\":\"A1001\"}");
        List<ToolCallRecord> records = recorder.end();

        assertThat(result).contains("flaky:ok:A1001");
        assertThat(tools.flakyAttempts()).isEqualTo(3);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).status()).isEqualTo(ToolCallRecord.Status.OK);
        assertThat(records.get(0).attempts()).isEqualTo(3);
        assertThat(records.get(0).retried()).isTrue();
        assertThat(records.get(0).degraded()).isFalse();
    }

    @Test
    void returnsFallbackInsteadOfThrowingWhenRetriesAreExhausted() {
        AgentProperties properties = new AgentProperties();
        properties.getResilience().setMaxAttempts(2);
        ToolCallRecorder recorder = new ToolCallRecorder(properties);
        ToolCallback callback = wrap(new SampleTools(), "boom", recorder, TestPolicies.of(properties));

        recorder.begin();
        String fallback = callback.call("{\"value\":\"x\"}");
        List<ToolCallRecord> records = recorder.end();

        assertThat(fallback).contains("\"fallback\":true").contains("工具 boom").contains("2 次");
        assertThat(records).hasSize(1);
        ToolCallRecord record = records.get(0);
        assertThat(record.status()).isEqualTo(ToolCallRecord.Status.ERROR);
        assertThat(record.attempts()).isEqualTo(2);
        assertThat(record.degraded()).isTrue();
        assertThat(record.error()).contains("工具内部错误");
    }

    @Test
    void marksTimedOutCallsAsTimeout() {
        AgentProperties properties = new AgentProperties();
        properties.getResilience().setMaxAttempts(1);
        properties.getResilience().setTimeoutMs(50);
        ToolCallRecorder recorder = new ToolCallRecorder(properties);
        ToolCallback callback = wrap(new SampleTools(), "slow", recorder, TestPolicies.of(properties));

        recorder.begin();
        String fallback = callback.call("{\"value\":\"x\"}");
        List<ToolCallRecord> records = recorder.end();

        assertThat(fallback).contains("\"fallback\":true").contains("超时");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).status()).isEqualTo(ToolCallRecord.Status.TIMEOUT);
        assertThat(records.get(0).degraded()).isTrue();
    }

    @Test
    void blocksCallsBeyondTheLimit() {
        AgentProperties properties = new AgentProperties();
        properties.setMaxToolCalls(1);
        ToolCallRecorder recorder = new ToolCallRecorder(properties);
        ToolCallback callback = wrap(new SampleTools(), "echo", recorder, TestPolicies.of(properties));

        recorder.begin();
        callback.call("{\"value\":\"first\"}");
        String blocked = callback.call("{\"value\":\"second\"}");
        List<ToolCallRecord> records = recorder.end();

        assertThat(blocked).contains("上限");
        assertThat(records).hasSize(2);
        assertThat(records.get(1).status()).isEqualTo(ToolCallRecord.Status.BLOCKED);
        assertThat(records.get(1).name()).isEqualTo("echo");
    }

    private static ToolCallback wrap(Object toolObject, String name,
                                     ToolCallRecorder recorder, ToolExecutionPolicy policy) {
        ToolCallback[] raw = MethodToolCallbackProvider.builder()
                .toolObjects(toolObject)
                .build()
                .getToolCallbacks();
        for (ToolCallback callback : raw) {
            if (name.equals(callback.getToolDefinition().name())) {
                return new RecordingToolCallback(callback, recorder, policy);
            }
        }
        throw new AssertionError("测试工具未注册：" + name);
    }

    static class SampleTools {

        private int flakyAttempts;

        @Tool(name = "echo", description = "回显输入值，仅用于测试")
        String echo(@ToolParam(description = "要回显的值") String value) {
            return "echo:" + value;
        }

        @Tool(name = "boom", description = "总是抛异常，仅用于测试错误记录与兜底")
        String boom(@ToolParam(description = "随便一个值") String value) {
            throw new IllegalStateException("工具内部错误");
        }

        @Tool(name = "flaky", description = "前两次失败第三次成功，仅用于测试重试")
        String flaky(@ToolParam(description = "随便一个值") String value) {
            flakyAttempts++;
            if (flakyAttempts < 3) {
                throw new IllegalStateException("暂时不可用");
            }
            return "flaky:ok:" + value;
        }

        @Tool(name = "slow", description = "睡 500 毫秒再返回，仅用于测试超时")
        String slow(@ToolParam(description = "随便一个值") String value) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("被中断");
            }
            return "slow:ok";
        }

        int flakyAttempts() {
            return flakyAttempts;
        }
    }
}
