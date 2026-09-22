package com.demetrius.fileagent.evaluation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationDatasetContractTest {

    @Test
    void shouldKeepVersionOneDatasetAtThirtyValidCases() {
        EvaluationFiles files = new EvaluationFiles(new ObjectMapper());

        var cases = files.loadCases(Path.of("src/main/resources/evaluation/v1/cases"));
        var gate = files.loadGateConfig(Path.of("src/main/resources/evaluation/v1/gate.json"));

        assertThat(cases).hasSize(30);
        assertThat(cases).extracting(EvaluationCase::category)
                .contains("FACT", "SEMANTIC", "TABLE", "CONFLICT", "NO_ANSWER", "PROMPT_INJECTION");
        assertThat(gate.minimumScores()).containsKeys("caseSuccessRate", "recall@10", "mrr", "ndcg@10");
    }

    @Test
    void shouldKeepAdaptiveDatasetAtTwelveValidCasesWithForcedGatesOnly() {
        EvaluationFiles files = new EvaluationFiles(new ObjectMapper());

        var cases = files.loadCases(Path.of("src/main/resources/evaluation/adaptive-v1/cases"));
        var gate = files.loadGateConfig(Path.of("src/main/resources/evaluation/adaptive-v1/gate.json"));

        assertThat(cases).hasSize(12);
        assertThat(cases).extracting(EvaluationCase::category)
                .contains("NO_RETRIEVAL", "SINGLE_HOP", "MULTI_HOP", "COMPARISON", "AGGREGATION",
                        "TIME_SENSITIVE", "PARTIAL_ZERO", "ZERO_RESULT", "DUPLICATE_QUERY",
                        "INVALID_PLAN", "RERANK_FALLBACK", "INFRA_FAILURE");
        assertThat(gate.minimumScores()).containsKeys("agent.runSuccessRate", "agent.budgetComplianceRate",
                "agent.toolWhitelistPassRate", "adaptive.queryCountComplianceRate", "adaptive.strategyComplianceRate");
        assertThat(gate.minimumScores()).doesNotContainKeys("adaptive.queryTypeAccuracy", "adaptive.subQuestionCoverage");
    }

    @Test
    void shouldKeepCalibratedAdaptiveDatasetSeparateFromVersionOne() {
        EvaluationFiles files = new EvaluationFiles(new ObjectMapper());
        var cases = files.loadCases(Path.of("src/main/resources/evaluation/adaptive-v2/cases"));
        var gate = files.loadGateConfig(Path.of("src/main/resources/evaluation/adaptive-v2/gate.json"));

        assertThat(cases).hasSize(13);
        assertThat(cases).extracting(EvaluationCase::category)
                .contains("MULTI_QUERY", "MULTI_HOP", "HISTORICAL_COMPARISON", "PARTIAL_ZERO");
        assertThat(cases).filteredOn(c -> c.id().equals("adaptive-multi-001"))
                .singleElement().satisfies(c -> assertThat(c.expected().expectedQueryType()).isEqualTo("MULTI_QUERY"));
        assertThat(cases).filteredOn(c -> c.id().equals("adaptive-hop-001"))
                .singleElement().satisfies(c -> {
                    assertThat(c.expected().expectedQueryType()).isEqualTo("MULTI_HOP");
                    assertThat(c.expected().expectedSubQuestions()).hasSize(2);
                });
        assertThat(cases).filteredOn(c -> c.id().equals("adaptive-time-001"))
                .singleElement().satisfies(c -> assertThat(c.expected().expectedQueryType()).isEqualTo("COMPARISON"));
        assertThat(cases).filteredOn(c -> c.id().equals("adaptive-partial-zero-001"))
                .singleElement().satisfies(c -> assertThat(c.expected().expectedQueryType()).isEqualTo("MULTI_QUERY"));
        assertThat(cases).extracting(c -> c.filters().ragName())
                .containsOnly("fileagent-eval-adaptive-v2");
        assertThat(gate.minimumScores()).containsKeys("agent.runSuccessRate", "agent.budgetComplianceRate",
                "agent.toolWhitelistPassRate", "adaptive.queryCountComplianceRate", "adaptive.strategyComplianceRate");
        assertThat(gate.minimumScores()).doesNotContainKeys("adaptive.queryTypeAccuracy", "adaptive.subQuestionCoverage");
    }
}
