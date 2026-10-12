package com.example.aikb.stream;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 流式输出配置，前缀 {@code app.stream}。 */
@Data
@ConfigurationProperties(prefix = "app.stream")
public class StreamProperties {

    /** SSE 连接的最长存活时间毫秒数；<=0 表示不设超时。 */
    private long timeoutMs = 300_000L;

    /**
     * 两次输出之间的最长静默时间毫秒数（含「第一个字」的等待）；<=0 表示不检查。
     *
     * <p>这是给流式输出兜底的：如果网关连不上、被限流或模型一直排队，界面上只会看到一个光标在闪。
     * 超过这个时间就主动结束本轮并推一个 error 事件，让用户看到「为什么没有回答」。
     */
    private long idleTimeoutMs = 90_000L;
}
