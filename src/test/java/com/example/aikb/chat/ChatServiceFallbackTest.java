package com.example.aikb.chat;

import com.example.aikb.observability.ModelCallObserver;
import com.example.aikb.observability.ObservabilityProperties;
import com.example.aikb.stream.StreamEvent;
import com.example.aikb.stream.StreamPayloads;
import com.example.aikb.stream.StreamProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 空流兜底：某些网关的流式接口会返回「消耗了 token 但 content 为空」的响应，
 * 这时要改用非流式再问一次，而不是让界面显示「无输出」。
 */
class ChatServiceFallbackTest {

    @Test
    void retriesWithoutStreamingWhenStreamHasNoContent() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        given(chatClient.prompt()).willReturn(spec);
        given(spec.messages(anyList())).willReturn(spec);
        given(spec.user(anyString())).willReturn(spec);
        given(spec.stream()).willReturn(streamSpec);
        given(spec.call()).willReturn(callSpec);
        // 流式：只有一个空内容的分片（网关就是这么返回的）
        given(streamSpec.chatResponse()).willReturn(Flux.just(response("", 1049, 82)));
        // 非流式：正常答案
        given(callSpec.chatResponse()).willReturn(response("未查询到商品，请确认 SKU。", 700, 40));

        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        given(builder.build()).willReturn(chatClient);

        ModelCallObserver observer = new ModelCallObserver(new ObservabilityProperties());
        ChatService service = new ChatService(builder,
                new ConversationMemoryService(new ChatMemoryProperties()), observer, new StreamProperties());
        ChatRequest request = new ChatRequest();
        request.setMessage("你知道小米11 urltra的售价吗");

        List<StreamEvent> events = service.stream(request).collectList().block();

        assertThat(events).extracting(StreamEvent::name).containsExactly("meta", "delta", "done");
        assertThat(((StreamPayloads.Delta) events.get(1).data()).text()).isEqualTo("未查询到商品，请确认 SKU。");
        assertThat(((StreamPayloads.Done) events.get(2).data()).estimatedCost()).isPositive();
        // 流式那次 + 非流式兜底那次，两条用量都记下来了
        assertThat(observer.summary().calls()).isEqualTo(2);
    }

    @Test
    void doesNotRetryWhenStreamHasContent() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        given(chatClient.prompt()).willReturn(spec);
        given(spec.messages(anyList())).willReturn(spec);
        given(spec.user(anyString())).willReturn(spec);
        given(spec.stream()).willReturn(streamSpec);
        given(streamSpec.chatResponse()).willReturn(Flux.just(response("你好", 5, 3)));

        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        given(builder.build()).willReturn(chatClient);

        ModelCallObserver observer = new ModelCallObserver(new ObservabilityProperties());
        ChatService service = new ChatService(builder,
                new ConversationMemoryService(new ChatMemoryProperties()), observer, new StreamProperties());
        ChatRequest request = new ChatRequest();
        request.setMessage("你好");

        List<StreamEvent> events = service.stream(request).collectList().block();

        assertThat(events).extracting(StreamEvent::name).containsExactly("meta", "delta", "done");
        assertThat(((StreamPayloads.Delta) events.get(1).data()).text()).isEqualTo("你好");
        assertThat(observer.summary().calls()).isEqualTo(1);
    }

    private static ChatResponse response(String text, int promptTokens, int completionTokens) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .metadata(ChatResponseMetadata.builder()
                        .model("test-model")
                        .usage(new DefaultUsage(promptTokens, completionTokens))
                        .build())
                .build();
    }
}
