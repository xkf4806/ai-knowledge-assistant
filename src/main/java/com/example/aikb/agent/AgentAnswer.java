package com.example.aikb.agent;

import com.example.aikb.agent.plan.Plan;
import com.example.aikb.observability.TokenUsage;

import java.util.List;

/**
 * Agent 问答结果：除了最终答案，还返回本轮调用过的工具明细。
 *
 * <p>{@code toolCalls} 让「模型到底调没调工具、传了什么参数、拿到什么结果、重试了几次」全程可见，
 * 既方便前端展示「思考过程」，也让工具调用可以被自动化测试断言。
 *
 * @param plan     本轮的规划步骤，说明「系统打算怎么查」
 * @param degraded 本轮是否发生过工具失败兜底（有工具以 ERROR / TIMEOUT 收场）
 * @param usage    本轮模型调用的 token 用量
 */
public record AgentAnswer(
        String sessionId,
        String answer,
        List<String> toolsUsed,
        List<ToolCallRecord> toolCalls,
        int turns,
        Plan plan,
        boolean degraded,
        TokenUsage usage) {
}
