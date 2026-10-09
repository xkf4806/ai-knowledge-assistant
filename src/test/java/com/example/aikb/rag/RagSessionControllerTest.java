package com.example.aikb.rag;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 3 周新增的状态/会话接口都不调用大模型，可以安全地做端到端测试。
 * 默认是内存向量库（app.rag.vector-store=memory），所以这些测试不需要 PostgreSQL。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RagSessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void statusReportsActiveVectorStore() throws Exception {
        mockMvc.perform(get("/api/rag/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vectorStore").value("memory"))
                .andExpect(jsonPath("$.topK").value(4))
                .andExpect(jsonPath("$.chunkStrategy").value("heading"));
    }

    @Test
    void clearsSessionWithoutCallingModel() throws Exception {
        mockMvc.perform(delete("/api/rag/sessions/session-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("session-1"))
                .andExpect(jsonPath("$.cleared").value(true));
    }

    @Test
    void rejectsBlankQuestion() throws Exception {
        mockMvc.perform(post("/api/rag/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("question 不能为空"));
    }
}
