package com.example.aikb.rag.parser;

/**
 * 文档解析结果。
 *
 * @param source      原始文件名
 * @param contentType Tika 探测到的 MIME 类型
 * @param text        抽取出的纯文本
 */
public record ParsedDocument(String source, String contentType, String text) {
}
