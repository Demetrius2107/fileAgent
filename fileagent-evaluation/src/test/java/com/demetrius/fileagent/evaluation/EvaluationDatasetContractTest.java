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
}
