package com.example.aikb.observability;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 观测接口：token 用量、估算成本与最近调用明细；都不调用模型、不花钱。
 */
@RestController
@RequestMapping("/api/observability")
public class ObservabilityController {

    private final ModelCallObserver observer;

    public ObservabilityController(ModelCallObserver observer) {
        this.observer = observer;
    }

    /** 用量总览：总 token / 估算费用 / 按入口与模型分解 / 最近调用明细。 */
    @GetMapping("/usage")
    public UsageSummary usage() {
        return observer.summary();
    }

    /** 清零累计用量，方便演示「从零开始跑一轮」。 */
    @DeleteMapping("/usage")
    public Map<String, Object> reset() {
        observer.reset();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reset", true);
        return body;
    }
}
