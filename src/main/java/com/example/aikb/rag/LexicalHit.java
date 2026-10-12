package com.example.aikb.rag;

import org.springframework.ai.document.Document;

/** BM25 关键词检索命中。 */
public record LexicalHit(Document document, double score) {
}
