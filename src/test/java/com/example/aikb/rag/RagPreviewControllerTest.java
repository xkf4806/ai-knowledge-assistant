package com.example.aikb.rag;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/rag/preview 不调用 embedding，可以安全地做端到端测试。 */
@SpringBootTest
@AutoConfigureMockMvc
class RagPreviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void previewReturnsAllStrategiesWithoutCallingEmbedding() throws Exception {
        String markdown = """
                # 员工手册

                ## 年假
                入职满 1 年的员工，每年享有 10 天带薪年假。

                ## 报销流程
                差旅费用需在行程结束后 15 个工作日内提交报销单。
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file", "handbook.md", "text/markdown", markdown.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/rag/preview").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("handbook.md"))
                .andExpect(jsonPath("$.strategies.length()").value(3))
                .andExpect(jsonPath("$.strategies[0].strategy").value("fixed"))
                .andExpect(jsonPath("$.strategies[1].strategy").value("heading"))
                .andExpect(jsonPath("$.strategies[1].chunks[0].heading").value("员工手册 > 年假"));
    }

    @Test
    void previewRejectsUnsupportedFormat() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "archive.zip", "application/zip", "dummy".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/rag/preview").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}
