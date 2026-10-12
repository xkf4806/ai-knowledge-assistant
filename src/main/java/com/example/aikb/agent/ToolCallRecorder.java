package com.example.aikb.agent;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具调用收集器。
 *
 * <p>Spring AI 的阻塞式 {@code call()} 会在调用线程上顺序执行工具，所以最初用 {@link ThreadLocal}
 * 就能把「一次提问里发生的所有工具调用」收集到一起。
 *
 * <p>流式 {@code stream()} 不一样：工具会在模型客户端的 Reactor 线程上执行，
 * 拿不到请求线程的 ThreadLocal。这里的做法是把本次会话的 ID 通过 {@code ToolContext}
 * 传给每个工具回调（Spring AI 会把 {@code toolContext(...)} 原样透传），
 * 回调再按 ID 找回同一个会话——工具调用因此仍然是「一轮一账」。
 *
 * <p>它同时兼做一道保险：单轮工具调用次数超过上限后拒绝继续执行，
 * 避免模型陷入反复调用的循环。
 */
@Component
public class ToolCallRecorder {

    /** 放进 ToolContext 的键；工具回调按这个键找回本轮会话。 */
    public static final String CONTEXT_KEY = "aikb.toolCallSession";

    private final AgentProperties properties;
    private final ThreadLocal<Session> current = new ThreadLocal<>();
    private final Map<String, Session> scoped = new ConcurrentHashMap<>();

    public ToolCallRecorder(AgentProperties properties) {
        this.properties = properties;
    }

    /** 开始收集本轮的工具调用（阻塞式路径）。 */
    public void begin() {
        current.set(newSession());
    }

    /** 结束收集并返回本轮记录；没有开始过则返回空列表。 */
    public List<ToolCallRecord> end() {
        Session session = current.get();
        current.remove();
        return session == null ? List.of() : List.copyOf(session.records);
    }

    /**
     * 开始收集本轮的工具调用（流式路径）：登记一个可跨线程找回的会话。
     *
     * <p>这里刻意不写 {@link ThreadLocal}：流式下工具在别的线程执行，写进去也用不上，
     * 反而可能在 Tomcat 线程复用时残留一个已经结束的会话。
     */
    public Scope beginScoped() {
        Session session = newSession();
        String id = UUID.randomUUID().toString();
        scoped.put(id, session);
        return new Scope(id);
    }

    /** 结束流式收集：返回本轮记录并清理登记；重复调用返回空列表。 */
    public List<ToolCallRecord> endScoped(Scope scope) {
        if (scope == null) {
            return List.of();
        }
        Session session = scoped.remove(scope.id());
        return session == null ? List.of() : List.copyOf(session.records);
    }

    /** 交给 {@code ChatClient.toolContext(...)}，让工具回调能在别的线程上找回本轮会话。 */
    public Map<String, Object> contextFor(Scope scope) {
        return scope == null ? Map.of() : Map.of(CONTEXT_KEY, scope.id());
    }

    /** 申请一次工具调用额度，超过上限返回 false。 */
    boolean tryAcquire() {
        Session session = current.get();
        return session != null && session.count.incrementAndGet() <= session.maxCalls;
    }

    /**
     * 申请一次额度（流式路径）。
     *
     * <p>找不到会话时**放行但不记录**：宁可少一条观测数据，也不能因为上下文没传到位就把工具挡掉。
     */
    boolean tryAcquire(ToolContext toolContext) {
        Session session = sessionFor(toolContext);
        if (session == null) {
            return true;
        }
        return session.count.incrementAndGet() <= session.maxCalls;
    }

    void record(ToolCallRecord record) {
        record(record, null);
    }

    void record(ToolCallRecord record, ToolContext toolContext) {
        Session session = sessionFor(toolContext);
        if (session != null) {
            session.records.add(record);
        }
    }

    int maxToolCalls() {
        return properties.getMaxToolCalls();
    }

    private Session sessionFor(ToolContext toolContext) {
        if (toolContext != null) {
            Object id = toolContext.getContext().get(CONTEXT_KEY);
            if (id != null) {
                Session session = scoped.get(id.toString());
                if (session != null) {
                    return session;
                }
            }
        }
        return current.get();
    }

    private Session newSession() {
        return new Session(properties.getMaxToolCalls());
    }

    /** 一次流式提问的会话标识，用完用它取回记录并清理。 */
    public record Scope(String id) {
    }

    private static final class Session {
        private final int maxCalls;
        private final AtomicInteger count = new AtomicInteger();
        private final List<ToolCallRecord> records = new ArrayList<>();

        private Session(int maxCalls) {
            this.maxCalls = maxCalls;
        }
    }
}
