package com.example.aikb.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查：只验证应用是否启动成功，不依赖大模型，无需 API Key。
 */
@RestController
@RequestMapping("/api")
public class PingController {

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("app", "ai-knowledge-assistant");
        body.put("week", 2);
        return body;
    }
}
