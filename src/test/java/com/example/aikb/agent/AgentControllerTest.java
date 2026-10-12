package com.example.aikb.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工具清单与会话接口都不调用模型，可以安全地做端到端测试（默认内存向量库，无需 PostgreSQL）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listsRegisteredToolsWithSchema() throws Exception {
        mockMvc.perform(get("/api/agent/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.maxToolCalls").value(6))
                .andExpect(jsonPath("$.plannerEnabled").value(true))
                .andExpect(jsonPath("$.restrictToolsToPlan").value(true))
                .andExpect(jsonPath("$.maxAttempts").value(3))
                .andExpect(jsonPath("$.toolTimeoutMs").value(2000))
                .andExpect(jsonPath("$.tools.length()").value(4))
                .andExpect(jsonPath("$.tools[0].name").value("checkInventory"))
                .andExpect(jsonPath("$.tools[1].name").value("lookupOrder"))
                .andExpect(jsonPath("$.tools[1].inputSchema").value(containsString("orderId")))
                .andExpect(jsonPath("$.tools[3].name").value("searchKnowledgeBase"));
    }

    @Test
    void previewsPlanWithoutCallingModel() throws Exception {
        mockMvc.perform(post("/api/agent/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"订单 A1003 能退货吗？再看看 SKU-3003 的库存。\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planned").value(true))
                .andExpect(jsonPath("$.tools[0]").value("lookupOrder"))
                .andExpect(jsonPath("$.tools[1]").value("searchKnowledgeBase"))
                .andExpect(jsonPath("$.tools[2]").value("checkInventory"))
                .andExpect(jsonPath("$.steps[0].tool").value("lookupOrder"))
                .andExpect(jsonPath("$.steps[3].tool").doesNotExist());
    }

    @Test
    void rejectsBlankQuestionInPlanPreview() throws Exception {
        mockMvc.perform(post("/api/agent/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("question 不能为空"));
    }

    @Test
    void rejectsBlankQuestion() throws Exception {
        mockMvc.perform(post("/api/agent/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("question 不能为空"));
    }

    @Test
    void clearsSessionWithoutCallingModel() throws Exception {
        mockMvc.perform(delete("/api/agent/sessions/session-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("session-a"))
                .andExpect(jsonPath("$.cleared").value(true));
    }
}
