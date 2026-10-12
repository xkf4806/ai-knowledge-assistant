package com.example.aikb.agent;

import com.example.aikb.agent.data.BusinessDataStore;
import com.example.aikb.agent.tool.InventoryTools;
import com.example.aikb.agent.tool.KnowledgeBaseTools;
import com.example.aikb.agent.tool.OrderTools;
import com.example.aikb.agent.tool.PricingTools;
import com.example.aikb.rag.RetrievalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentToolRegistryTest {

    private final BusinessDataStore store = new BusinessDataStore(
            new ObjectMapper().findAndRegisterModules(),
            new ClassPathResource("agent/orders.json"),
            new ClassPathResource("agent/inventory.json"));

    @Test
    void registersAllToolsInStableOrder() {
        List<String> names = names(new AgentProperties());

        assertThat(names).containsExactly(
                "checkInventory", "lookupOrder", "quotePrice", "searchKnowledgeBase");
    }

    @Test
    void exposesSchemaGeneratedFromToolAnnotations() {
        AgentToolRegistry registry = registry(new AgentProperties());
        ToolDescriptor lookup = registry.descriptors().stream()
                .filter(descriptor -> descriptor.name().equals("lookupOrder"))
                .findFirst()
                .orElseThrow();

        // 描述与入参 Schema 就是模型做出「调不调、怎么调」判断的全部依据
        assertThat(lookup.description()).contains("订单号");
        assertThat(lookup.inputSchema()).contains("orderId");
        assertThat(lookup.inputSchema()).contains("required");
    }

    @Test
    void respectsDisabledTools() {
        AgentProperties properties = new AgentProperties();
        properties.setDisabledTools(List.of("quotePrice", "lookupOrder"));

        AgentToolRegistry registry = registry(properties);

        assertThat(names(properties)).containsExactly("checkInventory", "searchKnowledgeBase");
        assertThat(registry.callbacks()).hasSize(2);
    }

    @Test
    void narrowsCallbacksToThePlannedTools() {
        AgentToolRegistry registry = registry(new AgentProperties());

        List<String> planned = Arrays.stream(
                        registry.callbacksFor(List.of("lookupOrder", "searchKnowledgeBase")))
                .map(callback -> callback.getToolDefinition().name())
                .toList();

        assertThat(planned).containsExactlyInAnyOrder("lookupOrder", "searchKnowledgeBase");
    }

    @Test
    void fallsBackToAllToolsWhenPlanIsEmptyOrFullyDisabled() {
        AgentProperties properties = new AgentProperties();
        properties.setDisabledTools(List.of("lookupOrder", "searchKnowledgeBase"));

        AgentToolRegistry registry = registry(properties);

        assertThat(registry.callbacksFor(List.of())).hasSize(2);
        // 计划里的工具恰好都被禁用时，退回全部已注册工具，而不是把能力清零
        assertThat(registry.callbacksFor(List.of("lookupOrder", "searchKnowledgeBase"))).hasSize(2);
    }

    private List<String> names(AgentProperties properties) {
        return registry(properties).descriptors().stream().map(ToolDescriptor::name).toList();
    }

    private AgentToolRegistry registry(AgentProperties properties) {
        return new AgentToolRegistry(
                new OrderTools(store, Clock.systemDefaultZone()),
                new InventoryTools(store),
                new PricingTools(store),
                new KnowledgeBaseTools(mock(RetrievalService.class)),
                new ToolCallRecorder(properties),
                TestPolicies.of(properties),
                properties);
    }
}
