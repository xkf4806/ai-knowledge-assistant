package com.example.aikb.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 模型 HTTP 客户端配置。
 *
 * <p>Spring AI 的 OpenAI 客户端里两条通路是分开的，要分别管住：
 * <ul>
 *   <li><b>嵌入 / 非流式对话</b> 走同步 {@link RestClient}（{@link #modelRestClientBuilder}）——
 *       加读取超时兜底，卡住的请求会尽快失败；</li>
 *   <li><b>流式对话</b> 走响应式 {@link WebClient}（{@link #modelWebClientBuilder}）——
 *       不能设总超时，否则长回答会被掐断；它的「静默兜底」交给 {@code StreamTimeouts} 那一层。</li>
 * </ul>
 *
 * <p>两条通路共用同一个 {@link HttpClient}，并且**固定使用 HTTP/1.1**：
 * 镜像里没有 reactor-netty / HttpComponents，实际用的是 JDK HttpClient，
 * 而它默认走 HTTP/2。实测在这套 WSL + docker bridge 链路上，HTTP/2 长连接会静默卡死
 * （curl 直接报 {@code HTTP/2 stream 0 was not closed cleanly: PROTOCOL_ERROR}），
 * 同一网络下 HTTP/1.1 完全正常，而 OpenAI 兼容网关本来就是短请求 + SSE。
 */
@Slf4j
@Configuration
public class HttpClientConfig {

    /** 两条通路共用的 JDK HttpClient（连接超时 + 协议版本）。 */
    @Bean
    public HttpClient modelHttpClient(AiClientProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(properties.isUseHttp2() ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(Math.max(1, properties.getConnectTimeoutMs())))
                .build();
        log.info("模型 HTTP 客户端：protocol={}，connect={}ms，sync read={}ms",
                properties.isUseHttp2() ? "HTTP/2" : "HTTP/1.1",
                properties.getConnectTimeoutMs(), properties.getReadTimeoutMs());
        return httpClient;
    }

    /**
     * 同步通路用（嵌入、非流式对话）。
     * 声明成 prototype，与 Spring Boot 自己的做法一致：每个客户端拿到自己的 builder，避免被互相改写。
     */
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public RestClient.Builder modelRestClientBuilder(HttpClient modelHttpClient, AiClientProperties properties) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(modelHttpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(Math.max(1, properties.getReadTimeoutMs())));
        return RestClient.builder().requestFactory(requestFactory);
    }

    /** 流式通路用：只带连接超时，不设总超时。 */
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public WebClient.Builder modelWebClientBuilder(HttpClient modelHttpClient) {
        ClientHttpConnector connector = new JdkClientHttpConnector(modelHttpClient);
        return WebClient.builder().clientConnector(connector);
    }
}
