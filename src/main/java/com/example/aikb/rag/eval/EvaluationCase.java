package com.example.aikb.rag.eval;

import java.util.List;

/**
 * 一条 RAG 评测样本。
 *
 * @param id              稳定 ID，便于报告里定位失败样本
 * @param question        用户问题
 * @param expectedSource  期望命中的文档
 * @param expectedHeading 期望命中的标题路径关键词；为空表示只校验文档
 * @param expectedKeywords 回答评测时希望答案覆盖的关键事实
 * @param referenceAnswer 标准答案，供人工复核
 */
public record EvaluationCase(String id,
                             String question,
                             String expectedSource,
                             String expectedHeading,
                             List<String> expectedKeywords,
                             String referenceAnswer) {
}
