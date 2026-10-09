package com.example.aikb.web;

import com.example.aikb.rag.parser.DocumentParseException;
import com.example.aikb.rag.parser.UnsupportedDocumentException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把文档解析错误与参数错误转成 400，返回可读的错误信息，而不是默认的 500 堆栈。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({UnsupportedDocumentException.class, DocumentParseException.class,
            IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> badRequest(RuntimeException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
