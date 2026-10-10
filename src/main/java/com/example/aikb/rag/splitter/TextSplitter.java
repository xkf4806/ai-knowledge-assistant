package com.example.aikb.rag.splitter;

import com.example.aikb.rag.SimpleTextSplitter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 可配置分块器：把解析后的纯文本切成适合 embedding 的片段。
 * 三种策略对应第 2 周的学习重点，固定长度切分的底层实现仍复用第 1 周的 {@link SimpleTextSplitter}。
 */
@Component
public class TextSplitter {

    /** Markdown 与常见纯文本标题：# 到 ###### + 空格 + 标题 */
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern HEADING_END_PUNCTUATION = Pattern.compile(".*[。！？；，,：:;!?].*$");

    public List<TextChunk> split(String text, ChunkingOptions options) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return List.of();
        }
        return switch (options.strategy()) {
            case FIXED -> fixed(normalized, null, options);
            case HEADING -> byHeading(normalized, options);
            case PARAGRAPH -> byParagraph(normalized, options);
        };
    }

    private List<TextChunk> fixed(String text, String heading, ChunkingOptions options) {
        List<TextChunk> chunks = new ArrayList<>();
        for (String part : SimpleTextSplitter.split(text, options.chunkSize(), options.overlap())) {
            if (!part.isEmpty()) {
                chunks.add(new TextChunk(withHeading(heading, part), heading));
            }
        }
        return chunks;
    }

    private List<TextChunk> byHeading(String text, ChunkingOptions options) {
        List<Section> sections = extractSections(text);
        if (sections.isEmpty()) {
            // 没有标题（如纯文本）时退化为固定长度切分，保证行为可预期。
            return fixed(text, null, options);
        }
        List<TextChunk> chunks = new ArrayList<>();
        for (Section section : sections) {
            chunks.addAll(fixed(section.body(), section.heading(), options));
        }
        return chunks;
    }

    private List<TextChunk> byParagraph(String text, ChunkingOptions options) {
        List<TextChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String raw : text.split("\\n\\s*\\n")) {
            String paragraph = raw.trim();
            if (paragraph.isEmpty()) {
                continue;
            }
            if (paragraph.length() > options.chunkSize()) {
                // 单段就超长：先落下已攒的片段，再把这一段按固定长度切开。
                flush(chunks, current);
                for (String part : SimpleTextSplitter.split(paragraph, options.chunkSize(), options.overlap())) {
                    if (!part.isEmpty()) {
                        chunks.add(new TextChunk(part, null));
                    }
                }
                continue;
            }
            if (current.length() > 0 && current.length() + paragraph.length() + 2 > options.chunkSize()) {
                flush(chunks, current);
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(paragraph);
        }
        flush(chunks, current);
        return chunks;
    }

    /** 按标题行切小节，并维护标题层级路径（如 员工手册 > 年假）。 */
    private List<Section> extractSections(String text) {
        List<Section> sections = new ArrayList<>();
        List<Heading> stack = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        boolean previousLineBlank = true;
        int plainHeadingCount = 0;
        for (String line : text.split("\n", -1)) {
            Matcher matcher = HEADING.matcher(line);
            if (matcher.matches()) {
                flush(sections, stack, body);
                int level = matcher.group(1).length();
                String title = matcher.group(2).trim();
                while (!stack.isEmpty() && stack.get(stack.size() - 1).level() >= level) {
                    stack.remove(stack.size() - 1);
                }
                stack.add(new Heading(level, title));
            } else if (isPlainTextHeading(line, previousLineBlank)) {
                flush(sections, stack, body);
                int level = plainHeadingCount++ == 0 ? 1 : 2;
                while (!stack.isEmpty() && stack.get(stack.size() - 1).level() >= level) {
                    stack.remove(stack.size() - 1);
                }
                stack.add(new Heading(level, line.trim()));
            } else {
                body.append(line).append('\n');
            }
            previousLineBlank = line.isBlank();
        }
        flush(sections, stack, body);
        return sections;
    }

    /**
     * 纯文本文档没有 Markdown 标记，用「空行分隔 + 短行 + 无句末标点」识别小节标题。
     * 首个纯文本标题视为文档标题，后续标题视为二级小节，拼出「文档 > 小节」路径。
     */
    private static boolean isPlainTextHeading(String line, boolean previousLineBlank) {
        if (!previousLineBlank) {
            return false;
        }
        String title = line == null ? "" : line.trim();
        return !title.isEmpty()
                && title.length() <= 24
                && !HEADING_END_PUNCTUATION.matcher(title).matches();
    }

    private static void flush(List<Section> sections, List<Heading> stack, StringBuilder body) {
        String content = body.toString().trim();
        body.setLength(0);
        if (content.isEmpty()) {
            return;
        }
        String heading = stack.isEmpty()
                ? null
                : stack.stream().map(Heading::title).collect(Collectors.joining(" > "));
        sections.add(new Section(heading, content));
    }

    private static void flush(List<TextChunk> chunks, StringBuilder current) {
        if (current.length() > 0) {
            chunks.add(new TextChunk(current.toString(), null));
            current.setLength(0);
        }
    }

    private static String withHeading(String heading, String body) {
        return heading == null || heading.isBlank() ? body : heading + "\n" + body;
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private record Heading(int level, String title) {
    }

    private record Section(String heading, String body) {
    }
}
