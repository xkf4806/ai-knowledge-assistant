package com.example.aikb.observability;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 观测接口本身不调用模型：这里直接往观测器里塞一条记录，再验证接口能读出来。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ObservabilityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ModelCallObserver observer;

    @Test
    void reportsRecordedUsage() throws Exception {
        observer.reset();
        ModelCallObserver.Call call = observer.startCall("rag", "session-x");
        call.observe(ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("ok"))))
                .metadata(ChatResponseMetadata.builder()
                        .model("test-model")
                        .usage(new DefaultUsage(200, 100, 300))
                        .build())
                .build());
        observer.finish(call);

        mockMvc.perform(get("/api/observability/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.currency").value("CNY"))
                .andExpect(jsonPath("$.calls").value(1))
                .andExpect(jsonPath("$.totalTokens").value(300))
                .andExpect(jsonPath("$.bySource.rag.totalTokens").value(300))
                .andExpect(jsonPath("$.byModel.test-model.calls").value(1))
                .andExpect(jsonPath("$.recentCalls[0].sessionId").value("session-x"));
    }

    @Test
    void resetsAccumulatedUsage() throws Exception {
        ModelCallObserver.Call call = observer.startCall("chat", "session-y");
        observer.finish(call);

        mockMvc.perform(delete("/api/observability/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reset").value(true));

        mockMvc.perform(get("/api/observability/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calls").value(0));
    }
}
