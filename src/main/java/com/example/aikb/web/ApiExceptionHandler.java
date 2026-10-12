package com.example.aikb.web;

import com.example.aikb.rag.parser.DocumentParseException;
import com.example.aikb.rag.parser.UnsupportedDocumentException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一把异常转成可读的 JSON：文档/参数错误 -> 400，其余运行期错误 -> 500。
 *
 * <p>补了一个兜底：模型网关报错（比如 401、超时）以前只会返回 Spring 默认的
 * 500 报文，前端拿不到任何原因；现在返回一段截断后的可读消息，前端直接显示在对话里。
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 返回给前端的错误文本上限，避免把整段供应商报文丢出去。 */
    private static final int MAX_MESSAGE_LENGTH = 300;

    @ExceptionHandler({UnsupportedDocumentException.class, DocumentParseException.class,
            IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> badRequest(RuntimeException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * 兜底处理：模型调用失败、内部状态异常等。消息会截断，且不返回堆栈，
     * 让前端能显示「为什么失败」而不是一句 Internal Server Error。
     *
     * <p>Spring 自己抛出的异常（404 找不到资源、400 报文解析失败）本身带状态码，
     * 这里用 {@link ErrorResponse} 把它们的状态码保留下来，只补上可读消息。
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> serverError(RuntimeException e) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String detail = null;
        if (e instanceof ErrorResponse errorResponse) {
            status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
            detail = errorResponse.getBody().getDetail();
        }
        String message = detail == null || detail.isBlank() ? describe(e) : detail;
        log.warn("请求处理失败（{}）：{}", status.value(), message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        String text = message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + message;
        return text.length() <= MAX_MESSAGE_LENGTH ? text : text.substring(0, MAX_MESSAGE_LENGTH) + "...";
    }
}
