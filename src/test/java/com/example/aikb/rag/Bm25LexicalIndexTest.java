package com.example.aikb.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Bm25LexicalIndexTest {

    @Test
    void ranksExactKeywordHitAndSupportsSourceFilter() {
        Bm25LexicalIndex index = new Bm25LexicalIndex();
        index.replaceSource("employee-handbook.md", List.of(document(
                "employee-handbook.md", 0, "员工手册 > 年假",
                "入职满 1 年的员工，每年享有 10 天带薪年假。")));
        index.replaceSource("refund-policy.md", List.of(document(
                "refund-policy.md", 0, "退货与退款政策 > 适用条件",
                "客户收到商品后 7 个自然日内可以申请无理由退货。")));

        List<LexicalHit> hits = index.search("无理由退货期限", null, 5);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).document().getMetadata().get("source"))
                .isEqualTo("refund-policy.md");
        assertThat(index.search("年假", "refund-policy.md", 5)).isEmpty();
        assertThat(index.search("年假", "employee-handbook.md", 5)).hasSize(1);
    }

    @Test
    void replacingSourceRemovesStaleChunks() {
        Bm25LexicalIndex index = new Bm25LexicalIndex();
        index.replaceSource("faq.txt", List.of(document(
                "faq.txt", 0, null, "旧版密码长度为 8 位。")));

        index.replaceSource("faq.txt", List.of(document(
                "faq.txt", 0, null, "新版密码长度不少于 12 位。")));

        assertThat(index.size()).isEqualTo(1);
        assertThat(index.search("12 位", null, 5)).hasSize(1);
        assertThat(index.search("旧版密码", null, 5))
                .noneMatch(hit -> hit.document().getText().contains("旧版"));
    }

    private static Document document(String source, int chunk, String heading, String text) {
        Map<String, Object> metadata = heading == null
                ? Map.of("source", source, "chunk", chunk)
                : Map.of("source", source, "chunk", chunk, "heading", heading);
        return Document.builder().text(text).metadata(metadata).build();
    }
}
