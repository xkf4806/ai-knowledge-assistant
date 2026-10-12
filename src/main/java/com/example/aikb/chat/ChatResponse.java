package com.example.aikb.chat;

import com.example.aikb.observability.TokenUsage;
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

    /** 本轮模型调用的 token 用量，用于前端展示与成本统计。 */
    private TokenUsage usage;

    public ChatResponse(String sessionId, String answer, int turns) {
        this(sessionId, answer, turns, null);
    }
}
