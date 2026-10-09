package com.example.aikb.rag.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 基于 Apache Tika 的解析器：由 Tika 自动探测格式并抽取纯文本，
 * 覆盖第 2 周要求的 PDF / Word / Markdown，也顺带支持 HTML、txt、Excel、PPT 等。
 */
@Slf4j
@Component
public class TikaDocumentParser implements DocumentParser {

    /** 用 TreeSet 保证报错信息里的格式顺序稳定、方便阅读 */
    private static final Set<String> SUPPORTED_EXTENSIONS = new TreeSet<>(Set.of(
            "pdf", "doc", "docx", "md", "markdown", "txt", "text",
            "html", "htm", "csv", "xls", "xlsx", "ppt", "pptx"));

    private static final String SUPPORTED_HINT = String.join(" / ", SUPPORTED_EXTENSIONS);

    private final Tika tika = new Tika();

    @Override
    public ParsedDocument parse(InputStream inputStream, String filename) {
        String extension = extensionOf(filename);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new UnsupportedDocumentException(
                    "不支持的文档格式：." + extension + "，当前支持：" + SUPPORTED_HINT);
        }
        try {
            String contentType = tika.detect(filename);
            String text = tika.parseToString(inputStream);
            if (text == null || text.isBlank()) {
                throw new DocumentParseException("未能从文档中抽取到文本：" + filename);
            }
            log.info("已解析文档 {}（{}），抽出 {} 个字符", filename, contentType, text.length());
            return new ParsedDocument(filename, contentType, text);
        } catch (IOException | TikaException e) {
            throw new DocumentParseException("解析文档失败：" + filename, e);
        }
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
