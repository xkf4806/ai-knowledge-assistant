package com.example.aikb.rag.parser;

/** 文档解析失败（损坏、加密、为空等）时抛出。 */
public class DocumentParseException extends RuntimeException {

    public DocumentParseException(String message) {
        super(message);
    }

    public DocumentParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
