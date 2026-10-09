package com.example.aikb.chat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    /** 会话 ID；不传则后端生成一个新的，下次带上它即可续上上下文。 */
    private String sessionId;

    private String message;

}
