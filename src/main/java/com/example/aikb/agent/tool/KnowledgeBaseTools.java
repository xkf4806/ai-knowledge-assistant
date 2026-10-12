package com.example.aikb.agent.tool;

import com.example.aikb.agent.model.KnowledgeSearchResult;
import com.example.aikb.rag.RetrievalService;
import com.example.aikb.rag.RetrievedChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把 RAG 检索包装成一个工具。
 *
 * <p>Agent 与 RAG 不是二选一：把「查知识库」也做成工具之后，模型就能在一次提问里
 * 既调业务工具（查订单），又调知识工具（查退货政策），这才是「企业助手」真正需要的能力。
 */
@Slf4j
@Component
public class KnowledgeBaseTools {

    private final RetrievalService retrievalService;

    public KnowledgeBaseTools(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @Tool(name = "searchKnowledgeBase",
            description = "在公司内部知识库（员工手册、退货退款政策、IT 支持文档等）中检索与问题相关的段落，"
                    + "返回带来源编号、来源位置与原文的片段。涉及政策、规定、流程、制度时调用它，"
                    + "再依据返回的原文作答并标注引用编号。")
    public KnowledgeSearchResult searchKnowledgeBase(
            @ToolParam(description = "要检索的自然语言问题，例如「定制商品可以退货吗」") String question) {
        List<RetrievedChunk> hits = retrievalService.retrieve(question, null, null);
        log.info("searchKnowledgeBase：question={}，命中 {} 段", question, hits.size());
        return KnowledgeSearchResult.from(question, hits);
    }
}
