package com.example.aikb.observability;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用量统计与估算费用：不调用模型，直接喂构造好的响应。
 */
class ModelCallObserverTest {

    private static ObservabilityProperties properties() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setPromptPricePer1M(2.0);
        properties.setCompletionPricePer1M(8.0);
        return properties;
    }

    @Test
    void accumulatesTokensAndEstimatesCost() {
        ModelCallObserver observer = new ModelCallObserver(properties());

        ModelCallObserver.Call call = observer.startCall("rag", "s1");
        call.observe(response("deepseek-v3", 1000, 500, 1500));
        ModelCallRecord record = observer.finish(call);

        // 1000/1e6*2 + 500/1e6*8 = 0.002 + 0.004 = 0.006
        assertThat(record.success()).isTrue();
        assertThat(record.model()).isEqualTo("deepseek-v3");
        assertThat(record.usage().totalTokens()).isEqualTo(1500);
        assertThat(record.estimatedCost()).isEqualTo(0.006);

        UsageSummary summary = observer.summary();
        assertThat(summary.calls()).isEqualTo(1);
        assertThat(summary.totalTokens()).isEqualTo(1500);
        assertThat(summary.estimatedCost()).isEqualTo(0.006);
        assertThat(summary.bySource()).containsKey("rag");
        assertThat(summary.bySource().get("rag").totalTokens()).isEqualTo(1500);
        assertThat(summary.byModel().get("deepseek-v3").calls()).isEqualTo(1);
        assertThat(summary.recentCalls()).hasSize(1);
    }

    @Test
    void mergesUsageAcrossStreamingChunks() {
        ModelCallObserver observer = new ModelCallObserver(properties());

        ModelCallObserver.Call call = observer.startCall("chat", "s2");
        // 流式：前面的分片只有内容、没有用量；最后一片才带上完整用量
        call.observe(response("deepseek-v3", 0, 0, 0));
        call.observe(response("deepseek-v3", 120, 80, 200));
        ModelCallRecord record = observer.finish(call);

        assertThat(record.promptTokens()).isEqualTo(120);
        assertThat(record.completionTokens()).isEqualTo(80);
        assertThat(record.totalTokens()).isEqualTo(200);
    }

    @Test
    void recordsFailedCallsWithoutTokens() {
        ModelCallObserver observer = new ModelCallObserver(properties());

        ModelCallObserver.Call call = observer.startCall("agent", "s3");
        ModelCallRecord record = observer.fail(call, new IllegalStateException("401 Unauthorized"));

        assertThat(record.success()).isFalse();
        assertThat(record.error()).contains("401 Unauthorized");
        assertThat(record.totalTokens()).isZero();

        UsageSummary summary = observer.summary();
        assertThat(summary.calls()).isEqualTo(1);
        assertThat(summary.failedCalls()).isEqualTo(1);
        assertThat(summary.bySource().get("agent").failedCalls()).isEqualTo(1);
    }

    @Test
    void keepsRecentCallsBoundedAndResets() {
        ObservabilityProperties properties = properties();
        properties.setMaxRecentCalls(3);
        properties.setRecentCallLimit(50);
        ModelCallObserver observer = new ModelCallObserver(properties);

        for (int i = 0; i < 5; i++) {
            ModelCallObserver.Call call = observer.startCall("chat", "s");
            call.observe(response("m", 10, 10, 20));
            observer.finish(call);
        }

        assertThat(observer.summary().calls()).isEqualTo(5);
        assertThat(observer.summary().recentCalls()).hasSize(3);

        observer.reset();
        UsageSummary afterReset = observer.summary();
        assertThat(afterReset.calls()).isZero();
        assertThat(afterReset.totalTokens()).isZero();
        assertThat(afterReset.recentCalls()).isEmpty();
        assertThat(afterReset.bySource()).isEmpty();
    }

    @Test
    void doesNotAccumulateWhenDisabledButStillReportsPerCallUsage() {
        ObservabilityProperties properties = properties();
        properties.setEnabled(false);
        ModelCallObserver observer = new ModelCallObserver(properties);

        ModelCallObserver.Call call = observer.startCall("rag", "s4");
        call.observe(response("m", 100, 100, 200));
        ModelCallRecord record = observer.finish(call);

        assertThat(record.totalTokens()).isEqualTo(200);
        assertThat(observer.summary().calls()).isZero();
        assertThat(observer.summary().enabled()).isFalse();
    }

    private static ChatResponse response(String model, int prompt, int completion, int total) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model(model)
                .usage(new DefaultUsage(prompt, completion, total))
                .build();
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("ok"))))
                .metadata(metadata)
                .build();
    }
}
