package com.example.aikb.rag.splitter;

/**
 * 分块结果。
 *
 * @param text    片段正文；HEADING 策略会在正文前带上标题路径，让 embedding 也能“看到”标题
 * @param heading 片段所属标题路径（如 {@code 员工手册 > 年假}），没有标题时为 {@code null}
 */
public record TextChunk(String text, String heading) {
}
