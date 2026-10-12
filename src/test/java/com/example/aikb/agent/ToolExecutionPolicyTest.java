package com.example.aikb.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutionPolicyTest {

    @Test
    void succeedsOnFirstAttempt() {
        AgentProperties properties = new AgentProperties();
        ToolExecutionPolicy policy = TestPolicies.of(properties);
        AtomicInteger calls = new AtomicInteger();

        ToolExecutionPolicy.Result result = policy.execute("demo", () -> {
            calls.incrementAndGet();
            return "ok";
        });

        assertThat(result.success()).isTrue();
        assertThat(result.value()).isEqualTo("ok");
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void retriesUntilSuccessWithinTheBudget() {
        AgentProperties properties = new AgentProperties();
        properties.getResilience().setMaxAttempts(3);
        ToolExecutionPolicy policy = TestPolicies.of(properties);
        AtomicInteger calls = new AtomicInteger();

        ToolExecutionPolicy.Result result = policy.execute("demo", () -> {
            if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("暂时不可用");
            }
            return "ok";
        });

        assertThat(result.success()).isTrue();
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void givesUpAfterMaxAttemptsAndReportsLastError() {
        AgentProperties properties = new AgentProperties();
        properties.getResilience().setMaxAttempts(2);
        ToolExecutionPolicy policy = TestPolicies.of(properties);
        AtomicInteger calls = new AtomicInteger();

        ToolExecutionPolicy.Result result = policy.execute("demo", () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("后端 500");
        });

        assertThat(result.success()).isFalse();
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.error()).contains("后端 500");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void timesOutSlowCalls() {
        AgentProperties properties = new AgentProperties();
        properties.getResilience().setMaxAttempts(1);
        properties.getResilience().setTimeoutMs(50);
        ToolExecutionPolicy policy = TestPolicies.of(properties);

        ToolExecutionPolicy.Result result = policy.execute("demo", () -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "太慢了";
        });

        assertThat(result.success()).isFalse();
        assertThat(result.timedOut()).isTrue();
        assertThat(result.attempts()).isEqualTo(1);
    }

    @Test
    void faultInjectionFailsEarlyAttemptsThenSucceeds() {
        AgentProperties properties = new AgentProperties();
        properties.getResilience().setFaultInjectionTools(List.of("lookupOrder"));
        properties.getResilience().setFaultInjectionFailures(2);
        ToolExecutionPolicy policy = TestPolicies.of(properties);
        AtomicInteger realCalls = new AtomicInteger();

        ToolExecutionPolicy.Result result = policy.execute("lookupOrder", () -> {
            realCalls.incrementAndGet();
            return "ok";
        });

        assertThat(result.success()).isTrue();
        assertThat(result.attempts()).isEqualTo(3);
        // 注入的失败不算真实调用，所以真实调用只发生了一次
        assertThat(realCalls.get()).isEqualTo(1);
    }

    @Test
    void faultInjectionLeavesOtherToolsAlone() {
        AgentProperties properties = new AgentProperties();
        properties.getResilience().setFaultInjectionTools(List.of("lookupOrder"));
        properties.getResilience().setFaultInjectionFailures(5);
        properties.getResilience().setMaxAttempts(2);
        ToolExecutionPolicy policy = TestPolicies.of(properties);

        ToolExecutionPolicy.Result result = policy.execute("checkInventory", () -> "ok");

        assertThat(result.success()).isTrue();
        assertThat(result.attempts()).isEqualTo(1);
    }
}
