package com.example.aikb.agent;

import com.example.aikb.agent.plan.AgentPlanner;
import com.example.aikb.agent.plan.Plan;
import com.example.aikb.chat.ConversationMemoryService;
import com.example.aikb.stream.SseStreams;
import com.example.aikb.stream.StreamProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/** Agent 接口：带工具调用的问答 + 计划预览 + 工具清单 + 会话清理。 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;
    private final AgentToolRegistry toolRegistry;
    private final AgentPlanner planner;
    private final AgentProperties properties;
    private final ConversationMemoryService memory;
    private final StreamProperties streamProperties;

    public AgentController(AgentService agentService,
                           AgentToolRegistry toolRegistry,
                           AgentPlanner planner,
                           AgentProperties properties,
                           ConversationMemoryService memory,
                           StreamProperties streamProperties) {
        this.agentService = agentService;
        this.toolRegistry = toolRegistry;
        this.planner = planner;
        this.properties = properties;
        this.memory = memory;
        this.streamProperties = streamProperties;
    }

    /** 带工具的问答：模型会自己决定是否调用工具、调用哪个工具。 */
    @PostMapping("/ask")
    public AgentAnswer ask(@RequestBody AgentRequest request) {
        requireQuestion(request);
        return agentService.ask(request);
    }

    /**
     * 流式问答：计划先下发，答案一段段推送，工具调用明细与用量随收尾事件返回。
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestBody AgentRequest request) {
        requireQuestion(request);
        return SseStreams.toEmitter(agentService.streamAsk(request), streamProperties.getTimeoutMs());
    }

    /**
     * 只看这一轮会怎么规划：返回有序步骤与计划内工具，不调用模型、不需要 Key。
     * 拿来演示「系统打算怎么查」、以及验证规划规则是否覆盖了你的提问方式。
     */
    @PostMapping("/plan")
    public Plan plan(@RequestBody AgentRequest request) {
        requireQuestion(request);
        return planner.plan(request.getQuestion());
    }

    /** 查看当前注册的工具（含 JSON Schema）。不调用模型、不需要 Key。 */
    @GetMapping("/tools")
    public Map<String, Object> tools() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", properties.isEnabled());
        body.put("maxToolCalls", properties.getMaxToolCalls());
        body.put("plannerEnabled", properties.getPlanner().isEnabled());
        body.put("restrictToolsToPlan", properties.getPlanner().isRestrictTools());
        body.put("maxAttempts", properties.getResilience().getMaxAttempts());
        body.put("toolTimeoutMs", properties.getResilience().getTimeoutMs());
        body.put("tools", toolRegistry.descriptors());
        return body;
    }

    /** 清空某个会话的对话记忆。 */
    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, Object> clearSession(@PathVariable String sessionId) {
        memory.clear(sessionId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("cleared", true);
        return body;
    }

    private static void requireQuestion(AgentRequest request) {
        if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
    }
}
