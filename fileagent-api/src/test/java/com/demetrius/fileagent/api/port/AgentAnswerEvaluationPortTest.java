package com.demetrius.fileagent.api.port;

import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.enums.RetrievalQueryType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AgentAnswerEvaluationPort} 评测契约扩展（Phase 2A）测试：Result 携带可空的
 * RetrievalObservation，RetrievalObservation 空列表兜底。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-09-20
 */
class AgentAnswerEvaluationPortTest {

    @Test
    void resultShouldCarryNullableRetrievalObservation() {
        AgentAnswerEvaluationPort.Result result = new AgentAnswerEvaluationPort.Result(
                "回答", false, List.of(), List.of(), 1, 1, List.of("search_docs"), 10L,
                AgentRunStatus.SUCCEEDED, null, null);

        assertThat(result.retrieval()).isNull();
    }

    @Test
    void resultShouldCarryRetrievalObservation() {
        AgentAnswerEvaluationPort.RetrievalObservation observation =
                new AgentAnswerEvaluationPort.RetrievalObservation(
                        RetrievalQueryType.MULTI_HOP, "MULTI_HOP", 2, 2,
                        List.of(3, 1), List.of("chunk-1", "chunk-2"), List.of("chunk-1"),
                        true, true, null);
        AgentAnswerEvaluationPort.Result result = new AgentAnswerEvaluationPort.Result(
                "回答", false, List.of(), List.of(), 1, 1, List.of("search_docs"), 10L,
                AgentRunStatus.SUCCEEDED, null, observation);

        assertThat(result.retrieval()).isSameAs(observation);
    }

    @Test
    void retrievalObservationShouldCarryAdaptiveFields() {
        AgentAnswerEvaluationPort.RetrievalObservation observation =
                new AgentAnswerEvaluationPort.RetrievalObservation(
                        RetrievalQueryType.AGGREGATION, "AGGREGATION", 1, 1,
                        List.of(3), List.of("chunk-1"), List.of("chunk-1"),
                        false, false, null);

        assertThat(observation.queryType()).isEqualTo(RetrievalQueryType.AGGREGATION);
        assertThat(observation.strategyId()).isEqualTo("AGGREGATION");
        assertThat(observation.plannedQueryCount()).isEqualTo(1);
        assertThat(observation.executedQueryCount()).isEqualTo(1);
        assertThat(observation.perQueryHitCounts()).containsExactly(3);
        assertThat(observation.candidateChunkIds()).containsExactly("chunk-1");
        assertThat(observation.finalChunkIds()).containsExactly("chunk-1");
        assertThat(observation.rerankRequested()).isFalse();
        assertThat(observation.rerankApplied()).isFalse();
        assertThat(observation.fallbackCode()).isNull();
    }

    @Test
    void retrievalObservationShouldFallBackToEmptyLists() {
        AgentAnswerEvaluationPort.RetrievalObservation observation =
                new AgentAnswerEvaluationPort.RetrievalObservation(
                        RetrievalQueryType.SINGLE_HOP, "SINGLE_HOP", 1, 0,
                        null, null, null, true, false, "RERANK_FAILED");

        assertThat(observation.perQueryHitCounts()).isEmpty();
        assertThat(observation.candidateChunkIds()).isEmpty();
        assertThat(observation.finalChunkIds()).isEmpty();
        assertThat(observation.rerankApplied()).isFalse();
        assertThat(observation.fallbackCode()).isEqualTo("RERANK_FAILED");
    }
}
