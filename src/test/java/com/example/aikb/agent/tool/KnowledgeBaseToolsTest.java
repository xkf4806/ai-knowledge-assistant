package com.example.aikb.agent.tool;

import com.example.aikb.agent.model.KnowledgeSearchResult;
import com.example.aikb.rag.RetrievalService;
import com.example.aikb.rag.RetrievedChunk;
import com.example.aikb.rag.SourceRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeBaseToolsTest {

    @Test
    void wrapsRetrievalHitsAsSnippetsWithLocation() {
        RetrievalService retrievalService = mock(RetrievalService.class);
        when(retrievalService.retrieve(anyString(), isNull(), isNull())).thenReturn(List.of(
                new RetrievedChunk(1, "定制类商品、生鲜类商品不支持退货。", 0.93,
                        new SourceRef("[1]", "refund-policy.md", 2, "不支持退货的情形", 0.93,
                                "定制类商品、生鲜类商品不支持退货。",
                                "refund-policy.md · 不支持退货的情形 · 第 3 段"))));
        KnowledgeBaseTools tools = new KnowledgeBaseTools(retrievalService);

        KnowledgeSearchResult result = tools.searchKnowledgeBase("定制商品可以退货吗");

        assertThat(result.found()).isTrue();
        assertThat(result.snippets()).hasSize(1);
        assertThat(result.snippets().get(0).label()).isEqualTo("[1]");
        assertThat(result.snippets().get(0).location()).contains("refund-policy.md");
        assertThat(result.snippets().get(0).text()).contains("定制类商品");
    }

    @Test
    void tellsTheModelWhenNothingWasFound() {
        RetrievalService retrievalService = mock(RetrievalService.class);
        when(retrievalService.retrieve(anyString(), isNull(), isNull())).thenReturn(List.of());
        KnowledgeBaseTools tools = new KnowledgeBaseTools(retrievalService);

        KnowledgeSearchResult result = tools.searchKnowledgeBase("公司食堂几点开饭");

        assertThat(result.found()).isFalse();
        assertThat(result.snippets()).isEmpty();
        assertThat(result.note()).contains("没有检索到");
    }
}
