package com.example.aikb.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Agent 相关的基础 Bean。
 *
 * <p>把 {@link Clock} 声明成 Bean 而不是在工具里直接写 {@code LocalDate.now()}，
 * 是为了让「距收货多少天」这类和时间相关的判断可测试：单元测试注入固定时钟即可复现任意日期。
 */
@Configuration
public class AgentConfig {

    @Bean
    public Clock agentClock() {
        return Clock.systemDefaultZone();
    }
}
