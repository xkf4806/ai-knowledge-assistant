package com.example.aikb.stream;

import com.example.aikb.chat.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 流式接口的 HTTP 层验证。
 *
 * <p>不调用模型：把 {@link ChatService} 换成返回固定事件流的替身，
 * 这样既能验证 SSE 的写出格式，也能验证「参数错误仍然是 400」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StreamingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    @Test
    void streamsMetaDeltaAndDoneEvents() throws Exception {
        given(chatService.stream(any())).willReturn(Flux.just(
                StreamEvent.meta(StreamPayloads.Meta.chat("session-1")),
                StreamEvent.delta("你好"),
                StreamEvent.delta("，世界"),
                StreamEvent.done(StreamPayloads.Done.chat("session-1", 1, null))));

        String body = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"打个招呼\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .contains("event:meta")
                .contains("\"sessionId\":\"session-1\"")
                .contains("event:delta")
                .contains("你好")
                .contains("event:done")
                .contains("\"turns\":1");
    }

    @Test
    void rejectsBlankMessageBeforeStreaming() throws Exception {
        mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("message 不能为空"));
    }

    @Test
    void rejectsBlankQuestionOnRagAndAgentStreams() throws Exception {
        mockMvc.perform(post("/api/rag/ask/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("question 不能为空"));

        mockMvc.perform(post("/api/agent/ask/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("question 不能为空"));
    }

    @Test
    void servesChatUiResources() throws Exception {
        // 静态资源由 Spring Boot 的 ResourceHttpRequestHandler 提供，MockMvc 只验证可访问；
        // 页面内容本身直接读 classpath 上的源文件校验。
        mockMvc.perform(get("/index.html")).andExpect(status().isOk());
        mockMvc.perform(get("/app.js")).andExpect(status().isOk());
        mockMvc.perform(get("/styles.css")).andExpect(status().isOk());

        String html = new ClassPathResource("static/index.html")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("企业知识库智能助手").contains("/app.js");
    }
}
