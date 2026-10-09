package com.example.aikb.rag;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AskRequest {

    private String question;

    /** 会话 ID；不传则新建会话，返回里会带回 */
    private String sessionId;

    /** 可选：只在该文件名范围内检索（元数据过滤） */
    private String source;
}
