package com.demetrius.fileagent.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QualityGateEvaluatorTest {

    @Test
    void shouldPassWhenAbsoluteThresholdsAndAllowedRegressionAreSatisfied() {
        EvaluationReport current = report(Map.of("recall@5", 0.84, "mrr", 0.70));
        EvaluationReport baseline = report(Map.of("recall@5", 0.86, "mrr", 0.72));
        QualityGateConfig config = new QualityGateConfig("1.0",
                Map.of("recall@5", 0.80, "mrr", 0.65), 0.03, List.of("recall@5", "mrr"));

        EvaluationReport.GateResult result = new QualityGateEvaluator().evaluate(current, config, baseline);

        assertThat(result.passed()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void shouldReportAbsoluteAndRegressionViolations() {
        EvaluationReport current = report(Map.of("recall@5", 0.84, "mrr", 0.60));
        EvaluationReport baseline = report(Map.of("recall@5", 0.86, "mrr", 0.72));
        QualityGateConfig config = new QualityGateConfig("1.0",
                Map.of("mrr", 0.65), 0.03, List.of("mrr"));

        EvaluationReport.GateResult result = new QualityGateEvaluator().evaluate(current, config, baseline);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).hasSize(2);
        assertThat(result.violations().get(0)).contains("mrr");
    }

    @Test
    void shouldRejectBaselineFromAnotherDatasetVersion() {
        EvaluationReport current = report(Map.of("mrr", 0.8));
        EvaluationReport baseline = new EvaluationReport("1.0", "v2", "2026-09-07T00:00:00Z",
                Map.of(), List.of(5), 1, 1, 0, Map.of("mrr", 0.8), Map.of("mrr", 1), List.of(), null);
        QualityGateConfig config = new QualityGateConfig("1.0", Map.of(), 0.03, List.of("mrr"));

        EvaluationReport.GateResult result = new QualityGateEvaluator().evaluate(current, config, baseline);

        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).singleElement().asString().contains("数据集版本不一致");
    }

    private static EvaluationReport report(Map<String, Double> scores) {
        return new EvaluationReport("1.0", "v1", "2026-09-07T00:00:00Z", Map.of(), List.of(1, 5),
                2, 2, 0, scores, Map.of("mrr", 2), List.of(), null);
    }
}
