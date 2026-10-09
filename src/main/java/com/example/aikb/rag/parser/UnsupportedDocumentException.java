package com.example.aikb.rag.parser;

/** 上传了不支持的文档格式时抛出。 */
public class UnsupportedDocumentException extends RuntimeException {

    public UnsupportedDocumentException(String message) {
        super(message);
    }
}
