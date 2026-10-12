package com.example.aikb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 模型 HTTP 客户端配置，前缀 {@code app.ai-client}。 */
@Data
@ConfigurationProperties(prefix = "app.ai-client")
public class AiClientProperties {

    /** 建立连接的超时毫秒数；连不上就快速失败，不要干等系统默认超时。 */
    private int connectTimeoutMs = 5000;

    /** 单次同步请求的读取超时毫秒数（嵌入、非流式对话）。 */
    private int readTimeoutMs = 60000;

    /**
     * 是否使用 HTTP/2。
     *
     * <p>默认关闭：JDK HttpClient 默认走 HTTP/2（ALPN 协商），但在 WSL / 容器的 NAT 链路上
     * HTTP/2 长连接容易「连上了、响应一直不来」（实测 curl 会报
     * {@code HTTP/2 stream 0 was not closed cleanly: PROTOCOL_ERROR}），同一个网络的 HTTP/1.1 完全正常。
     * 模型网关基本都是短请求 + SSE，HTTP/1.1 足够，也更稳。
     */
    private boolean useHttp2 = false;
}
