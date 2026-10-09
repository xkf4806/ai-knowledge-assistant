package com.example.aikb.chat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 多轮对话记忆配置（第 3 周）。 */
@Data
@ConfigurationProperties(prefix = "app.chat.memory")
public class ChatMemoryProperties {

    /** 每个会话保留的最近消息条数，一问一答算 2 条。 */
    private int maxMessages = 10;
}
