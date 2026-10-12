package com.example.aikb.agent;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 工具执行的健壮性策略：超时、重试（指数退避）与失败兜底。
 *
 * <p>只做了「次数上限保护」，工具一旦抛异常就直接冒泡，整轮提问可能以 500 收场。
 * 真实的内网系统会超时、会偶发失败，所以这里补上三件事：
 * <ol>
 *   <li><b>超时</b>：工具放到独立线程上执行，超过 {@code timeout-ms} 就取消，避免一个慢依赖拖垮整轮；</li>
 *   <li><b>重试</b>：失败后按 {@code initial-backoff-ms × multiplier^n} 退避重试，最多 {@code max-attempts} 次；</li>
 *   <li><b>故障注入</b>：配置里可以把某些工具的「前 N 次调用」设成必失败，用来现场演示重试与兜底。</li>
 * </ol>
 *
 * <p>注意它只负责「怎么执行」，不负责「怎么记录」——记录仍由 {@link RecordingToolCallback} 完成，
 * 这样超时/重试的结果也能进入 {@code toolCalls} 被观测到。
 */
@Slf4j
@Component
public class ToolExecutionPolicy {

    private final AgentProperties.Resilience config;
    private final ExecutorService executor;
    private final Sleeper sleeper;

    @Autowired
    public ToolExecutionPolicy(AgentProperties properties) {
        this(properties, newDaemonExecutor(), ToolExecutionPolicy::sleepQuietly);
    }

    /** 供单元测试注入可控的线程池与「睡眠器」，让退避等待不必真的睡。 */
    ToolExecutionPolicy(AgentProperties properties, ExecutorService executor, Sleeper sleeper) {
        this.config = properties.getResilience();
        this.executor = executor;
        this.sleeper = sleeper;
    }

    /**
     * 按配置执行一次工具调用，失败时重试，重试耗尽后返回失败结果（而不是抛异常）。
     *
     * @param toolName 工具名，用于日志与故障注入匹配
     * @param action   真正调用工具的代码
     */
    public Result execute(String toolName, Supplier<String> action) {
        int maxAttempts = Math.max(1, config.getMaxAttempts());
        int attempts = 0;
        Throwable lastError = null;
        boolean timedOut = false;

        while (attempts < maxAttempts) {
            attempts++;
            if (injectFailure(toolName, attempts)) {
                lastError = new IllegalStateException("注入的工具故障（第 " + attempts + " 次尝试）");
                timedOut = false;
                log.warn("工具 {} 第 {} 次尝试命中故障注入", toolName, attempts);
            } else {
                try {
                    String value = invoke(action);
                    if (attempts > 1) {
                        log.info("工具 {} 重试后成功：attempts={}", toolName, attempts);
                    }
                    return Result.ok(value, attempts);
                } catch (TimeoutException e) {
                    lastError = e;
                    timedOut = true;
                    log.warn("工具 {} 第 {} 次尝试超时（{} ms）", toolName, attempts, config.getTimeoutMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Result.failure(attempts, false, "工具调用被中断");
                } catch (ExecutionException e) {
                    lastError = e.getCause() == null ? e : e.getCause();
                    timedOut = false;
                } catch (RuntimeException e) {
                    lastError = e;
                    timedOut = false;
                }
            }
            sleepBackoff(attempts, maxAttempts);
        }

        String error = describe(lastError);
        log.warn("工具 {} 重试 {} 次后仍失败：{}", toolName, attempts, error);
        return Result.failure(attempts, timedOut, error);
    }

    private String invoke(Supplier<String> action)
            throws TimeoutException, InterruptedException, ExecutionException {
        long timeoutMs = config.getTimeoutMs();
        if (timeoutMs <= 0) {
            return action.get();
        }
        Future<String> future = executor.submit(action::get);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    private boolean injectFailure(String toolName, int attempt) {
        List<String> tools = config.getFaultInjectionTools();
        if (tools == null || tools.isEmpty() || attempt > Math.max(0, config.getFaultInjectionFailures())) {
            return false;
        }
        return tools.stream().anyMatch(name -> name != null && name.equalsIgnoreCase(toolName));
    }

    private void sleepBackoff(int attempt, int maxAttempts) {
        if (attempt >= maxAttempts) {
            return;
        }
        long backoff = Math.round(config.getInitialBackoffMs()
                * Math.pow(config.getBackoffMultiplier(), Math.max(0, attempt - 1)));
        if (backoff <= 0) {
            return;
        }
        try {
            sleeper.sleep(backoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        String type = error.getClass().getSimpleName();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }

    private static ExecutorService newDaemonExecutor() {
        int size = Math.max(2, Runtime.getRuntime().availableProcessors());
        return Executors.newFixedThreadPool(size, runnable -> {
            Thread thread = new Thread(runnable, "agent-tool-exec");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void sleepQuietly(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** 一次「带重试」调用的最终结果。 */
    public record Result(String value, boolean success, int attempts, boolean timedOut, String error) {

        static Result ok(String value, int attempts) {
            return new Result(value, true, attempts, false, null);
        }

        static Result failure(int attempts, boolean timedOut, String error) {
            return new Result(null, false, attempts, timedOut, error);
        }
    }

    /** 退避等待，抽出来是为了让单元测试可以跳过真实等待。 */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
