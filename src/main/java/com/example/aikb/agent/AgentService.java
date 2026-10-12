package com.example.aikb.agent;

import com.example.aikb.agent.plan.AgentPlanner;
import com.example.aikb.agent.plan.Plan;
import com.example.aikb.chat.ConversationMemoryService;
import com.example.aikb.observability.ModelCallObserver;
import com.example.aikb.observability.ModelCallRecord;
import com.example.aikb.stream.ChatResponses;
import com.example.aikb.stream.EmptyModelResponseException;
import com.example.aikb.stream.StreamEvent;
import com.example.aikb.stream.StreamPayloads;
import com.example.aikb.stream.StreamProperties;
import com.example.aikb.stream.StreamTimeouts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Agent 编排（含多步规划、工具健壮性与流式输出）。
 *
 * <p>「编排循环」看起来很深奥，其实在 Spring AI 里就是给 ChatClient 挂上工具：
 * 模型返回工具调用请求 → Spring AI 执行对应 Java 方法 → 把结果回灌给模型 → 模型继续判断，
 * 直到给出不再需要工具的最终答复。我们要做的只有三件事：
 * <ol>
 *   <li>把工具注册进去（{@link AgentToolRegistry}）；再按 {@link Plan} 收敛到本轮要用的几个；</li>
 *   <li>用系统提示词说清「什么时候该用哪个工具」；</li>
 *   <li>记录这一轮到底调了什么（{@link ToolCallRecorder}），让过程可解释。</li>
 * </ol>
 *
 * <p>流式版本有个关键差异：工具会在 Reactor 线程上执行，
 * 所以本轮的工具调用会话要通过 {@code toolContext} 传下去，ThreadLocal 在这里指望不上。
 */
@Slf4j
@Service
public class AgentService {

    private static final String PROMPT_RESOURCE = "prompts/agent-prompt.txt";

    private final ChatClient chatClient;
    private final ConversationMemoryService memory;
    private final AgentToolRegistry toolRegistry;
    private final ToolCallRecorder recorder;
    private final AgentPlanner planner;
    private final AgentProperties properties;
    private final ModelCallObserver observer;
    private final StreamProperties streamProperties;
    private final String systemPrompt;

    public AgentService(ChatClient.Builder chatClientBuilder,
                        ConversationMemoryService memory,
                        AgentToolRegistry toolRegistry,
                        ToolCallRecorder recorder,
                        AgentPlanner planner,
                        AgentProperties properties,
                        ModelCallObserver observer,
                        StreamProperties streamProperties) {
        this.chatClient = chatClientBuilder.build();
        this.memory = memory;
        this.toolRegistry = toolRegistry;
        this.recorder = recorder;
        this.planner = planner;
        this.properties = properties;
        this.observer = observer;
        this.streamProperties = streamProperties;
        this.systemPrompt = loadSystemPrompt();
    }

    private static String loadSystemPrompt() {
        try {
            return new ClassPathResource(PROMPT_RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("加载 Agent 系统提示词失败: " + PROMPT_RESOURCE, e);
        }
    }

    public AgentAnswer ask(AgentRequest request) {
        requireEnabled();
        String question = request.getQuestion();
        String sessionId = memory.resolveSessionId(request.getSessionId());
        List<Message> history = memory.history(sessionId);

        RunResult result = runOnce(question, history, sessionId);
        memory.record(sessionId, question, result.answer());
        log.info("Agent 完成一轮：sessionId={}，计划 {} 步，暴露 {} 个工具，调用工具 {} 次 {}",
                sessionId, result.plan().steps().size(), result.exposedTools(),
                result.toolCalls().size(), result.toolsUsed());
        return new AgentAnswer(sessionId, result.answer(), result.toolsUsed(), result.toolCalls(),
                memory.turnCount(sessionId), result.plan(), result.degraded(), result.record().usage());
    }

    /**
     * 一轮完整的阻塞式调用：规划 → 按计划收敛工具 → 把步骤写进提示词 → 调模型。
     *
     * <p>阻塞式 {@code call()} 在同一线程上顺序执行工具，所以这里用 {@link ToolCallRecorder} 的
     * ThreadLocal 收集本轮工具调用即可。抽出来是为了让「流式输出为空」时能原样重跑一遍。
     */
    private RunResult runOnce(String question, List<Message> history, String sessionId) {
        Plan plan = planner.plan(question);
        ToolCallback[] callbacks = resolveCallbacks(plan);
        String system = systemPromptWithPlan(plan);

        recorder.begin();
        ModelCallObserver.Call call = observer.startCall("agent", sessionId);
        String answer;
        List<ToolCallRecord> toolCalls;
        ModelCallRecord record;
        try {
            ChatResponse response = chatClient.prompt()
                    .system(system)
                    .messages(history)
                    .user(question)
                    .toolCallbacks(callbacks)
                    .call()
                    .chatResponse();
            call.observe(response);
            answer = ChatResponses.text(response);
            record = observer.finish(call);
        } catch (RuntimeException e) {
            observer.fail(call, e);
            throw e;
        } finally {
            toolCalls = recorder.end();
        }
        return new RunResult(answer, toolsUsed(toolCalls), toolCalls, plan, degraded(toolCalls),
                record, callbacks.length);
    }

    /**
     * 流式问答：{@code meta（含计划）→ delta × N → done（含工具调用明细与用量）}。
     *
     * <p>规划与工具收敛在推送开始前完成；工具在本轮的实际调用记录，
     * 通过 {@code toolContext} 里的会话 ID 跨线程回收，最后随 {@code done} 一起下发。
     */
    public Flux<StreamEvent> streamAsk(AgentRequest request) {
        requireEnabled();
        String question = request.getQuestion();
        String sessionId = memory.resolveSessionId(request.getSessionId());
        List<Message> history = memory.history(sessionId);

        Plan plan = planner.plan(question);
        ToolCallback[] callbacks = resolveCallbacks(plan);
        String system = systemPromptWithPlan(plan);

        ToolCallRecorder.Scope scope = recorder.beginScoped();
        ModelCallObserver.Call call = observer.startCall("agent", sessionId);
        StringBuilder buffer = new StringBuilder();

        // 工具执行期间没有 delta，静默时限要留得比单次工具调用更宽（默认 90 秒）。
        Flux<StreamEvent> body = StreamTimeouts.guardIdle(chatClient.prompt()
                .system(system)
                .messages(history)
                .user(question)
                .toolCallbacks(callbacks)
                .toolContext(recorder.contextFor(scope))
                .stream()
                .chatResponse()
                .doOnNext(call::observe)
                .mapNotNull(response -> appendDelta(response, buffer)), streamProperties.getIdleTimeoutMs());

        return Flux.concat(
                        Flux.just(StreamEvent.meta(StreamPayloads.Meta.agent(sessionId, plan))),
                        body,
                        Flux.defer(() -> finishStream(sessionId, question, history, buffer, scope, plan, call)))
                .doOnError(error -> {
                    recorder.endScoped(scope);
                    observer.fail(call, error);
                })
                .onErrorResume(error -> Flux.just(StreamEvent.error(error)));
    }

    /**
     * 正常收尾；如果这一轮流式输出一个字符都没有（某些网关会返回「消耗了 token 但 content 为空」的响应），
     * 就改用非流式把同一轮完整重跑一遍（含规划与工具调用），把结果补成一段 delta 推给前端。
     */
    private Flux<StreamEvent> finishStream(String sessionId, String question, List<Message> history,
                                           StringBuilder buffer, ToolCallRecorder.Scope scope,
                                           Plan plan, ModelCallObserver.Call call) {
        List<ToolCallRecord> streamedCalls = recorder.endScoped(scope);
        String streamed = buffer.toString();
        if (!streamed.isBlank()) {
            memory.record(sessionId, question, streamed);
            log.info("Agent 流式完成一轮：sessionId={}，计划 {} 步，调用工具 {} 次",
                    sessionId, plan.steps().size(), streamedCalls.size());
            return Flux.just(doneEvent(sessionId, streamedCalls, plan, observer.finish(call)));
        }

        // 这一轮流式调用确实消耗了 token 却什么都没产出，单独记一条失败，便于在用量面板里看出异常。
        observer.fail(call, new EmptyModelResponseException());
        log.warn("模型流式输出为空，改用非流式重跑一轮（含工具调用）：sessionId={}", sessionId);
        return Flux.defer(() -> {
            RunResult result = runOnce(question, history, sessionId);
            if (result.answer() == null || result.answer().isBlank()) {
                throw new EmptyModelResponseException();
            }
            memory.record(sessionId, question, result.answer());
            log.info("Agent 非流式兜底完成：sessionId={}，计划 {} 步，调用工具 {} 次 {}",
                    sessionId, result.plan().steps().size(), result.toolCalls().size(), result.toolsUsed());
            return Flux.just(StreamEvent.delta(result.answer()),
                    doneEvent(sessionId, result.toolCalls(), result.plan(), result.record()));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private StreamEvent doneEvent(String sessionId, List<ToolCallRecord> toolCalls,
                                  Plan plan, ModelCallRecord record) {
        return StreamEvent.done(StreamPayloads.Done.agent(sessionId, memory.turnCount(sessionId),
                toolsUsed(toolCalls), toolCalls, plan, degraded(toolCalls), record));
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Agent 功能已关闭（app.agent.enabled=false）");
        }
    }

    private ToolCallback[] resolveCallbacks(Plan plan) {
        if (plan.isEmpty() || !properties.getPlanner().isRestrictTools()) {
            return toolRegistry.callbacks();
        }
        return toolRegistry.callbacksFor(plan.tools());
    }

    private String systemPromptWithPlan(Plan plan) {
        String hint = planner.promptHint(plan);
        return hint.isBlank() ? systemPrompt : systemPrompt + "\n\n" + hint;
    }

    private static List<String> toolsUsed(List<ToolCallRecord> toolCalls) {
        return toolCalls.stream()
                .filter(ToolCallRecord::succeeded)
                .map(ToolCallRecord::name)
                .distinct()
                .toList();
    }

    private static boolean degraded(List<ToolCallRecord> toolCalls) {
        return toolCalls.stream().anyMatch(ToolCallRecord::degraded);
    }

    private static StreamEvent appendDelta(ChatResponse response, StringBuilder buffer) {
        String text = ChatResponses.text(response);
        if (text.isEmpty()) {
            return null;
        }
        buffer.append(text);
        return StreamEvent.delta(text);
    }

    /** 一轮阻塞式调用的结果，供 ask 与流式兜底共用。 */
    private record RunResult(String answer,
                             List<String> toolsUsed,
                             List<ToolCallRecord> toolCalls,
                             Plan plan,
                             boolean degraded,
                             ModelCallRecord record,
                             int exposedTools) {
    }
}
