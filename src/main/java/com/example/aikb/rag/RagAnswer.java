package com.example.aikb.rag;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * RAG 问答结果（第 3 周：多轮对话 + 引用溯源）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagAnswer {

    /** 会话 ID；下次带上它即可续上上下文 */
    private String sessionId;

    private String answer;

    /** 本次答案可引用的来源片段（编号与答案里的 [n] 对应） */
    private List<SourceRef> sources;

    /** 答案里实际引用到的编号，如 [1, 2]；用于校验模型是否真的用了资料 */
    private List<Integer> citations;

    /** 当前会话累计轮数（一问一答算一轮） */
    private int turns;

}
