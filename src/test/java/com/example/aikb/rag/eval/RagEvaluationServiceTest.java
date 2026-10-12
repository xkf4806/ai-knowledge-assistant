package com.example.aikb.rag.eval;

import com.example.aikb.chat.ConversationMemoryService;
import com.example.aikb.rag.RagService;
import com.example.aikb.rag.RetrievalMode;
import com.example.aikb.rag.RetrievalService;
import com.example.aikb.rag.RetrievedChunk;
import com.example.aikb.rag.SourceRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagEvaluationServiceTest {

    @Test
    void computesRetrievalMetricsFromDataset() {
        RetrievalService retrievalService = mock(RetrievalService.class);
        RagService ragService = mock(RagService.class);
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        RagEvaluationService service = new RagEvaluationService(
                retrievalService, ragService, memory, new ObjectMapper());

        List<EvaluationCase> dataset = service.dataset();
        when(retrievalService.retrieve(any(String.class), isNull(), eq(RetrievalMode.VECTOR)))
                .thenAnswer(invocation -> {
                    String question = invocation.getArgument(0);
                    EvaluationCase testCase = dataset.stream()
                            .filter(item -> item.question().equals(question))
                            .findFirst()
                            .orElseThrow();
                    return List.of(new RetrievedChunk(
                            1,
                            testCase.referenceAnswer(),
                            0.9,
                            new SourceRef(
                                    "[1]",
                                    testCase.expectedSource(),
                                    0,
                                    testCase.expectedHeading(),
                                    0.9,
                                    testCase.referenceAnswer(),
                                    testCase.expectedSource())));
                });

        EvaluationSummary summary = service.evaluate(RetrievalMode.VECTOR, false);

        assertThat(dataset).hasSizeGreaterThanOrEqualTo(20);
        assertThat(summary.getTotal()).isEqualTo(dataset.size());
        assertThat(summary.getHits()).isEqualTo(dataset.size());
        assertThat(summary.getHitRate()).isEqualTo(1.0);
        assertThat(summary.getMrr()).isEqualTo(1.0);
        assertThat(summary.getPrecisionAtK()).isEqualTo(1.0);
        assertThat(summary.isAnswersEvaluated()).isFalse();
    }
}
