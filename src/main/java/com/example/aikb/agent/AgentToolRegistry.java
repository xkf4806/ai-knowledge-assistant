package com.example.aikb.agent;

import com.example.aikb.agent.tool.InventoryTools;
import com.example.aikb.agent.tool.KnowledgeBaseTools;
import com.example.aikb.agent.tool.OrderTools;
import com.example.aikb.agent.tool.PricingTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 工具注册表：把标注了 {@link org.springframework.ai.tool.annotation.Tool} 的 Java 方法
 * 统一变成模型可调用的 {@link ToolCallback}，再逐个包上记录装饰器。
 *
 * <p>把「注册」集中在一处的好处：
 * <ul>
 *   <li>{@code /api/agent/tools} 能把模型看到的工具（含 JSON Schema）直接展示出来；</li>
 *   <li>想按场景裁剪能力，只改配置里的 {@code app.agent.disabled-tools}；
 *       又多了「按本轮计划裁剪」的 {@link #callbacksFor};</li>
 *   <li>加工具只需要再加一个 {@code @Tool} 方法，编排逻辑不用动。</li>
 * </ul>
 */
@Slf4j
@Component
public class AgentToolRegistry {

    private final ToolCallback[] callbacks;
    private final List<ToolDescriptor> descriptors;

    public AgentToolRegistry(OrderTools orderTools,
                             InventoryTools inventoryTools,
                             PricingTools pricingTools,
                             KnowledgeBaseTools knowledgeBaseTools,
                             ToolCallRecorder recorder,
                             ToolExecutionPolicy policy,
                             AgentProperties properties) {
        ToolCallback[] raw = MethodToolCallbackProvider.builder()
                .toolObjects(orderTools, inventoryTools, pricingTools, knowledgeBaseTools)
                .build()
                .getToolCallbacks();

        Set<String> disabled = Set.copyOf(properties.getDisabledTools());
        this.callbacks = Arrays.stream(raw)
                .filter(callback -> !disabled.contains(callback.getToolDefinition().name()))
                .map(callback -> (ToolCallback) new RecordingToolCallback(callback, recorder, policy))
                .sorted(Comparator.comparing(callback -> callback.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);
        this.descriptors = Arrays.stream(callbacks)
                .map(AgentToolRegistry::toDescriptor)
                .toList();
        log.info("已注册 {} 个 Agent 工具：{}", callbacks.length,
                descriptors.stream().map(ToolDescriptor::name).toList());
    }

    /** 供 ChatClient 使用的工具回调（已按配置裁剪、并包好记录装饰器）。 */
    public ToolCallback[] callbacks() {
        return callbacks.clone();
    }

    /**
     * 只返回计划内的工具：模型看不到本轮用不到的工具，自然也就调不错。
     *
     * <p>若计划里的工具全被 {@code app.agent.disabled-tools} 屏蔽掉了，就退回全部已注册工具——
     * 计划只是「建议」，不该把助手的能力整个清零。
     */
    public ToolCallback[] callbacksFor(Collection<String> allowedTools) {
        if (allowedTools == null || allowedTools.isEmpty()) {
            return callbacks();
        }
        Set<String> allowed = Set.copyOf(allowedTools);
        ToolCallback[] filtered = Arrays.stream(callbacks)
                .filter(callback -> allowed.contains(callback.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);
        if (filtered.length == 0) {
            log.warn("计划内的工具都被禁用（{}），退回全部已注册工具", allowed);
            return callbacks();
        }
        return filtered;
    }

    /** 工具清单，供 /api/agent/tools 展示。 */
    public List<ToolDescriptor> descriptors() {
        return descriptors;
    }

    private static ToolDescriptor toDescriptor(ToolCallback callback) {
        return new ToolDescriptor(
                callback.getToolDefinition().name(),
                callback.getToolDefinition().description(),
                callback.getToolDefinition().inputSchema());
    }
}
