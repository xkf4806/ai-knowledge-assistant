package com.example.aikb.rag.splitter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextSplitterTest {

    private final TextSplitter splitter = new TextSplitter();

    @Test
    void fixedSplitKeepsOverlapBetweenChunks() {
        String text = "0123456789".repeat(10); // 100 个字符
        ChunkingOptions options = new ChunkingOptions(ChunkingStrategy.FIXED, 40, 10);

        List<TextChunk> chunks = splitter.split(text, options);

        assertThat(chunks).hasSize(3);
        assertThat(chunks).allSatisfy(c -> assertThat(c.text().length()).isLessThanOrEqualTo(40));
        String first = chunks.get(0).text();
        String second = chunks.get(1).text();
        assertThat(first.substring(first.length() - 10)).isEqualTo(second.substring(0, 10));
    }

    @Test
    void headingSplitKeepsHeadingPathInChunk() {
        String markdown = """
                # 员工手册

                ## 年假
                入职满 1 年的员工，每年享有 10 天带薪年假。

                ## 报销流程
                差旅费用需在行程结束后 15 个工作日内提交报销单。
                """;
        ChunkingOptions options = new ChunkingOptions(ChunkingStrategy.HEADING, 200, 20);

        List<TextChunk> chunks = splitter.split(markdown, options);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).heading()).isEqualTo("员工手册 > 年假");
        assertThat(chunks.get(0).text()).startsWith("员工手册 > 年假").contains("10 天带薪年假");
        assertThat(chunks.get(1).heading()).isEqualTo("员工手册 > 报销流程");
    }

    @Test
    void headingSplitFallsBackToFixedWhenNoHeading() {
        String text = "a".repeat(100);

        List<TextChunk> chunks = splitter.split(text, new ChunkingOptions(ChunkingStrategy.HEADING, 40, 10));

        assertThat(chunks).hasSize(3);
        assertThat(chunks).allSatisfy(c -> assertThat(c.heading()).isNull());
    }

    @Test
    void paragraphSplitPacksSmallParagraphsUpToTargetSize() {
        String text = """
                第一段内容。
                第二段内容。

                第三段内容超出示例范围。
                """;

        List<TextChunk> chunks = splitter.split(text, new ChunkingOptions(ChunkingStrategy.PARAGRAPH, 20, 5));

        assertThat(chunks).hasSize(2);
        assertThat(chunks).allSatisfy(c -> assertThat(c.text().length()).isLessThanOrEqualTo(20));
        assertThat(chunks.get(0).text()).contains("第一段内容").contains("第二段内容");
    }

    @Test
    void rejectsInvalidOptions() {
        assertThatThrownBy(() -> new ChunkingOptions(ChunkingStrategy.FIXED, 40, 40))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlap");
        assertThatThrownBy(() -> new ChunkingOptions(ChunkingStrategy.FIXED, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunkSize");
    }

    @Test
    void rejectsUnknownStrategy() {
        assertThatThrownBy(() -> ChunkingStrategy.from("semantic"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("semantic");
    }
}
