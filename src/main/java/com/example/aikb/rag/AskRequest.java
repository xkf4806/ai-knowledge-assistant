package com.example.aikb.rag;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class AskRequest {

    private String question;

    /** 会话 ID；不传则新建会话，返回里会带回 */
    private String sessionId;

    /** 可选：只在该文件名范围内检索（元数据过滤） */
    private String source;

    /** 可选：覆盖本次检索模式（vector / hybrid / hybrid-rerank），用于演示与评测对比 */
    private String retrievalMode;

    public AskRequest(String question, String sessionId, String source) {
        this(question, sessionId, source, null);
    }

    public AskRequest(String question, String sessionId, String source, String retrievalMode) {
        this.question = question;
        this.sessionId = sessionId;
        this.source = source;
        this.retrievalMode = retrievalMode;
    }
}
