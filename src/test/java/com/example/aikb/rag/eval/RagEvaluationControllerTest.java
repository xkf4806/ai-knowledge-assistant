package com.example.aikb.rag.eval;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 评测集与模式校验不调用模型，可以安全地做接口测试。 */
@SpringBootTest
@AutoConfigureMockMvc
class RagEvaluationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesEvaluationDataset() throws Exception {
        mockMvc.perform(get("/api/rag/eval/dataset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(26))
                .andExpect(jsonPath("$[0].id").value("hr-annual-leave-days"))
                .andExpect(jsonPath("$[0].expectedSource").value("employee-handbook.md"));
    }

    @Test
    void rejectsUnknownRetrievalModeBeforeCallingEmbedding() throws Exception {
        mockMvc.perform(post("/api/rag/eval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modes\":[\"semantic\"],\"includeAnswers\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "不支持的检索模式：semantic，可选 vector / hybrid / hybrid-rerank"));
    }
}
