package com.example.aikb.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 引用校验属于纯逻辑，单独测掉，避免把不存在的编号当成有效来源返给前端。 */
class RagServiceCitationTest {

    @Test
    void keepsOnlyValidInRangeCitationsInOrder() {
        assertThat(RagService.extractCitations("年假为 10 天 [1]，报销需 15 个工作日 [2]。", 3))
                .containsExactly(1, 2);
        assertThat(RagService.extractCitations("重复引用 [2]，又说了一遍 [2] 和 [1]。", 3))
                .containsExactly(1, 2);
    }

    @Test
    void dropsOutOfRangeCitations() {
        assertThat(RagService.extractCitations("这段其实没有依据 [9]。", 2)).isEmpty();
    }

    @Test
    void handlesAnswerWithoutCitation() {
        assertThat(RagService.extractCitations("根据现有资料无法回答该问题。", 2)).isEmpty();
        assertThat(RagService.extractCitations(null, 2)).isEmpty();
    }
}
