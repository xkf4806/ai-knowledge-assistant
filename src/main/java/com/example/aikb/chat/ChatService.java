package com.example.aikb.chat;

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
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 基础对话服务：不接知识库，用来验证模型连通性与多轮记忆。
 *
 * <p>补了两件事：阻塞式与流式两种写法共用同一份会话记忆；
 * 两种写法都会通过 {@link ModelCallObserver} 记录本轮 token 用量与耗时。
 */
@Slf4j
@Service
public class ChatService {

    private final ChatClient chatClient;
    private final ConversationMemoryService memory;
    private final ModelCallObserver observer;
    private final StreamProperties streamProperties;

    public ChatService(ChatClient.Builder chatClientBuilder,
                       ConversationMemoryService memory,
                       ModelCallObserver observer,
                       StreamProperties streamProperties) {
        this.chatClient = chatClientBuilder.build();
        this.memory = memory;
        this.observer = observer;
        this.streamProperties = streamProperties;
    }

    /** 一次性拿到完整回答。 */
    public ChatResponse chat(ChatRequest request) {
        String sessionId = memory.resolveSessionId(request.getSessionId());
        List<Message> history = memory.history(sessionId);
        ModelCallObserver.Call call = observer.startCall("chat", sessionId);

        String answer;
        ModelCallRecord record;
        try {
            // 注意：chat 包里的 ChatResponse 是本项目的 DTO，Spring AI 的响应要写全限定名。
            org.springframework.ai.chat.model.ChatResponse response = chatClient.prompt()
                    .messages(history)
                    .user(request.getMessage())
                    .call()
                    .chatResponse();
            call.observe(response);
            answer = ChatResponses.text(response);
            record = observer.finish(call);
        } catch (RuntimeException e) {
            observer.fail(call, e);
            throw e;
        }

        memory.record(sessionId, request.getMessage(), answer);
        return new ChatResponse(sessionId, answer, memory.turnCount(sessionId), record.usage());
    }

    /** 流式回答：meta -> 若干 delta -> done。 */
    public Flux<StreamEvent> stream(ChatRequest request) {
        String sessionId = memory.resolveSessionId(request.getSessionId());
        List<Message> history = memory.history(sessionId);
        ModelCallObserver.Call call = observer.startCall("chat", sessionId);
        StringBuilder buffer = new StringBuilder();

        // 兜底：网关没响应时不要让界面永远只有一个光标，静默超时后推 error 事件。
        Flux<StreamEvent> body = StreamTimeouts.guardIdle(chatClient.prompt()
                .messages(history)
                .user(request.getMessage())
                .stream()
                .chatResponse()
                .doOnNext(call::observe)
                .mapNotNull(response -> appendDelta(response, buffer)), streamProperties.getIdleTimeoutMs());

        return Flux.concat(
                        Flux.just(StreamEvent.meta(StreamPayloads.Meta.chat(sessionId))),
                        body,
                        Flux.defer(() -> finishStream(sessionId, request, history, buffer, call)))
                .doOnError(error -> observer.fail(call, error))
                .onErrorResume(error -> Flux.just(StreamEvent.error(error)));
    }

    /** 正常收尾；如果这一轮流式输出一个字符都没有，改用非流式重试一次（见 {@link #retryWithoutStreaming}）。 */
    private Flux<StreamEvent> finishStream(String sessionId, ChatRequest request,
                                           List<Message> history, StringBuilder buffer,
                                           ModelCallObserver.Call call) {
        String streamed = buffer.toString();
        if (!streamed.isBlank()) {
            return Flux.just(StreamEvent.done(StreamPayloads.Done.chat(
                    sessionId, memory.turnCount(sessionId), finishCall(call, sessionId, request.getMessage(), streamed))));
        }
        log.warn("模型流式输出为空，改用非流式重试一次：sessionId={}", sessionId);
        return Flux.defer(() -> {
            String answer = nonStreamingAnswer(history, request.getMessage(), sessionId);
            if (answer == null || answer.isBlank()) {
                throw new EmptyModelResponseException();
            }
            ModelCallRecord record = finishCall(call, sessionId, request.getMessage(), answer);
            return Flux.just(StreamEvent.delta(answer),
                    StreamEvent.done(StreamPayloads.Done.chat(sessionId, memory.turnCount(sessionId), record)));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 写记忆 + 落一条用量记录。 */
    private ModelCallRecord finishCall(ModelCallObserver.Call call, String sessionId, String question, String answer) {
        memory.record(sessionId, question, answer);
        return observer.finish(call);
    }

    /** 非流式再问一次，作为「流式返回空内容」的兜底；这次调用单独记一条用量。 */
    private String nonStreamingAnswer(List<Message> history, String message, String sessionId) {
        ModelCallObserver.Call call = observer.startCall("chat", sessionId);
        try {
            org.springframework.ai.chat.model.ChatResponse response = chatClient.prompt()
                    .messages(history)
                    .user(message)
                    .call()
                    .chatResponse();
            call.observe(response);
            observer.finish(call);
            return ChatResponses.text(response);
        } catch (RuntimeException e) {
            observer.fail(call, e);
            throw e;
        }
    }

    /** 把一片响应追加到缓冲区；没有内容的分片返回 null，由 mapNotNull 丢掉。 */
    static StreamEvent appendDelta(org.springframework.ai.chat.model.ChatResponse response, StringBuilder buffer) {
        String text = ChatResponses.text(response);
        if (text.isEmpty()) {
            return null;
        }
        buffer.append(text);
        return StreamEvent.delta(text);
    }
}
