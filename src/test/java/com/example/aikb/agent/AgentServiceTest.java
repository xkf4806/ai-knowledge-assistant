package com.example.aikb.agent;

import com.example.aikb.agent.plan.AgentPlanner;
import com.example.aikb.chat.ConversationMemoryService;
import com.example.aikb.observability.ModelCallObserver;
import com.example.aikb.observability.ObservabilityProperties;
import com.example.aikb.stream.StreamProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AgentServiceTest {

    private AgentService service(AgentProperties properties) {
        return new AgentService(
                mock(ChatClient.Builder.class),
                mock(ConversationMemoryService.class),
                mock(AgentToolRegistry.class),
                new ToolCallRecorder(properties),
                new AgentPlanner(properties),
                properties,
                new ModelCallObserver(new ObservabilityProperties()),
                new StreamProperties());
    }

    @Test
    void refusesToRunWhenDisabled() {
        AgentProperties properties = new AgentProperties();
        properties.setEnabled(false);

        assertThatThrownBy(() -> service(properties).ask(new AgentRequest("订单 A1001 能退吗", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Agent 功能已关闭");
    }

    @Test
    void loadsAgentSystemPromptFromClasspath() {
        // 构造函数会读取 prompts/agent-prompt.txt；读不到会抛异常，这里能构造成功即说明资源就位
        assertThat(service(new AgentProperties())).isNotNull();
    }
}
