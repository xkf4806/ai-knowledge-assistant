package com.example.aikb.rag;

import com.example.aikb.observability.TokenUsage;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * RAG 问答结果。
 */
@Data
@NoArgsConstructor
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

    /** 本轮实际使用的检索模式 */
    private String retrievalMode;

    /** 本轮生成答案的 token 用量；检索不到资料时为 null（没调用模型） */
    private TokenUsage usage;

    public RagAnswer(String sessionId, String answer, List<SourceRef> sources,
                     List<Integer> citations, int turns) {
        this(sessionId, answer, sources, citations, turns, null);
    }

    public RagAnswer(String sessionId, String answer, List<SourceRef> sources,
                     List<Integer> citations, int turns, String retrievalMode) {
        this(sessionId, answer, sources, citations, turns, retrievalMode, null);
    }

    public RagAnswer(String sessionId, String answer, List<SourceRef> sources,
                     List<Integer> citations, int turns, String retrievalMode, TokenUsage usage) {
        this.sessionId = sessionId;
        this.answer = answer;
        this.sources = sources;
        this.citations = citations;
        this.turns = turns;
        this.retrievalMode = retrievalMode;
        this.usage = usage;
    }
}
