package com.example.aikb.rag.parser;

import java.io.InputStream;

/**
 * 文档解析器：把任意受支持格式的字节流解析成纯文本。
 * 第 2 周用 Tika 统一实现；以后若要单独优化 PDF（如 OCR），可以再加一个实现而不动上层。
 */
public interface DocumentParser {

    ParsedDocument parse(InputStream inputStream, String filename);
}
