package com.example.aikb.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** Agent 与工具调用配置，前缀 {@code app.agent}。 */
@Data
@ConfigurationProperties(prefix = "app.agent")
public class AgentProperties {

    /** 是否启用 Agent 接口；关掉后 /api/agent/ask 直接返回错误，方便对照演示。 */
    private boolean enabled = true;

    /** 单轮提问允许的最大工具调用次数，超过后拦截并提示模型基于已有信息作答。 */
    private int maxToolCalls = 6;

    /**
     * 需要屏蔽的工具名。
     * 例如只想演示知识库问答时，把 lookupOrder、checkInventory、quotePrice 加进来即可。
     */
    private List<String> disabledTools = new ArrayList<>();

    /** 工具执行健壮性：超时、重试与故障注入。 */
    private Resilience resilience = new Resilience();

    /** 任务规划：把问题拆成有序步骤，并据此收敛可调用工具。 */
    private Planner planner = new Planner();

    /** 工具执行策略配置，前缀 {@code app.agent.resilience}。 */
    @Data
    public static class Resilience {

        /** 单个工具在一次提问内最多尝试几次（含首次）；1 表示不重试。 */
        private int maxAttempts = 3;

        /** 第一次重试前的等待毫秒数。 */
        private long initialBackoffMs = 50;

        /** 退避倍数：第 n 次重试等待 initialBackoffMs × multiplier^(n-1)。 */
        private double backoffMultiplier = 2.0;

        /** 单次工具调用超时毫秒数；<=0 表示不启用超时（直接在调用线程执行）。 */
        private long timeoutMs = 2000;

        /**
         * 故障注入：这些工具的前 {@link #faultInjectionFailures} 次调用被强制失败，
         * 用来现场演示重试与兜底。生产环境留空即可。
         */
        private List<String> faultInjectionTools = new ArrayList<>();

        /** 故障注入的失败次数；配合 {@link #faultInjectionTools} 使用。 */
        private int faultInjectionFailures = 0;
    }

    /** 任务规划配置，前缀 {@code app.agent.planner}。 */
    @Data
    public static class Planner {

        /** 是否启用规则式规划器；关掉后不再生成计划，工具选择完全交给模型。 */
        private boolean enabled = true;

        /** 是否只把计划内的工具交给模型（最小权限）；关掉后仍生成计划，但保留全部工具。 */
        private boolean restrictTools = true;
    }
}
