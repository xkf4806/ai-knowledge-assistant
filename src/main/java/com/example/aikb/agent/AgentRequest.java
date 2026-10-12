package com.example.aikb.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Agent 提问请求。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentRequest {

    private String question;

    /** 会话 ID；不传则新建，返回里会带回，下次带上即可续上多轮上下文。 */
    private String sessionId;
}
