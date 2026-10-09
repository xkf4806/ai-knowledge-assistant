package com.example.aikb.chat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    private String sessionId;

    private String answer;

    /** 当前会话累计轮数（一问一答算一轮），用于前端展示与调试。 */
    private int turns;

}
