package com.example.aikb.agent.model;

import com.example.aikb.rag.RetrievedChunk;
import com.example.aikb.rag.SourceRef;

import java.util.List;

/**
 * searchKnowledgeBase 工具的返回结构：把 RAG 检索结果按「带来源编号的原文片段」
 * 交给模型，让模型既能引用，也能自己决定还需不需要查别的工具。
 */
public record KnowledgeSearchResult(String question, boolean found, List<Snippet> snippets, String note) {

    public record Snippet(String label, String location, String text) {
    }

    public static KnowledgeSearchResult from(String question, List<RetrievedChunk> hits) {
        if (hits.isEmpty()) {
            return new KnowledgeSearchResult(question, false, List.of(),
                    "知识库中没有检索到与该问题相关的资料，请如实告知用户，不要编造。");
        }
        List<Snippet> snippets = hits.stream()
                .map(KnowledgeSearchResult::toSnippet)
                .toList();
        return new KnowledgeSearchResult(question, true, snippets, null);
    }

    private static Snippet toSnippet(RetrievedChunk hit) {
        SourceRef source = hit.sourceRef();
        String location = source == null ? null : source.getLocation();
        return new Snippet(hit.label(), location, hit.text());
    }
}
