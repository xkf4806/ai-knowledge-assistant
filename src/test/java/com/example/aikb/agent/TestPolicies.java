package com.example.aikb.agent;

import java.util.concurrent.Executors;

/**
 * 测试用的 {@link ToolExecutionPolicy} 工厂：
 * 用「不真的等待」的退避器，避免重试测试真的睡在那里；线程池用守护线程，不拖住测试进程。
 */
final class TestPolicies {

    private TestPolicies() {
    }

    static ToolExecutionPolicy of(AgentProperties properties) {
        return new ToolExecutionPolicy(properties, Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "test-tool-exec");
            thread.setDaemon(true);
            return thread;
        }), millis -> {
        });
    }
}
