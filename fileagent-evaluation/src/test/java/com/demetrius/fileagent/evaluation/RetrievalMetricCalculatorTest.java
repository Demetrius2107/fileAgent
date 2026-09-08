package com.demetrius.fileagent.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalMetricCalculatorTest {

    private final RetrievalMetricCalculator calculator = new RetrievalMetricCalculator(List.of(1, 3, 5));

    @Test
    void shouldCalculateRankingMetricsWithoutCountingDuplicateMatchesTwice() {
        EvaluationCase evaluationCase = evaluationCase(List.of(
                expected("policy.md", 3),
                expected("handbook.md", 1)
        ));
        EvaluationObservation observation = observation(List.of(
                observed("noise.md"),
                observed("policy.md"),
                observed("policy.md"),
                observed("handbook.md")
        ));

        Map<String, Double> scores = calculator.calculate(evaluationCase, observation);

        assertThat(scores.get("hitRate@1")).isZero();
        assertThat(scores.get("recall@3")).isEqualTo(0.5);
        assertThat(scores.get("precision@3")).isCloseTo(1.0 / 3.0, within(0.0001));
        assertThat(scores.get("mrr")).isEqualTo(0.5);
        assertThat(scores.get("recall@5")).isEqualTo(1.0);
        assertThat(scores.get("precision@5")).isEqualTo(0.5);
        assertThat(scores.get("ndcg@3")).isCloseTo(0.5788, within(0.001));
        assertThat(scores.get("ndcg@5")).isCloseTo(0.6352, within(0.001));
    }

    @Test
    void shouldMeasureEmptyRetrievalForNoAnswerCase() {
        EvaluationCase evaluationCase = evaluationCase(List.of());

        assertThat(calculator.calculate(evaluationCase, observation(List.of()))
                .get("noAnswerEmptyRetrieval")).isEqualTo(1.0);
        assertThat(calculator.calculate(evaluationCase, observation(List.of(observed("noise.md"))))
                .get("noAnswerEmptyRetrieval")).isZero();
    }

    @Test
    void shouldPreferSpecificSourceWhenExpectedLocatorsOverlap() {
        EvaluationCase evaluationCase = evaluationCase(List.of(
                new EvaluationCase.ExpectedSource(null, null, "sales.csv", null, null, null, 1),
                new EvaluationCase.ExpectedSource(null, null, "sales.csv", null, null, 2, 3)
        ));
        EvaluationObservation observation = observation(List.of(
                new EvaluationObservation.ObservedSource(null, null, "sales.csv", null, null,
                        null, 2, null, 1.0),
                new EvaluationObservation.ObservedSource(null, null, "sales.csv", null, null,
                        null, 4, null, 0.9)
        ));

        assertThat(calculator.calculate(evaluationCase, observation).get("recall@3")).isEqualTo(1.0);
    }

    private static EvaluationCase evaluationCase(List<EvaluationCase.ExpectedSource> sources) {
        return new EvaluationCase("1.0", "case-1", "FACT", List.of(), "问题", List.of(),
                new EvaluationCase.Filters("eval", "baseline", null),
                new EvaluationCase.Expected(true, sources, List.of(), List.of()));
    }

    private static EvaluationCase.ExpectedSource expected(String filename, int relevance) {
        return new EvaluationCase.ExpectedSource(null, null, filename, null, null, null, relevance);
    }

    private static EvaluationObservation.ObservedSource observed(String filename) {
        return new EvaluationObservation.ObservedSource(null, null, filename, null, null,
                null, null, null, 1.0);
    }

    private static EvaluationObservation observation(List<EvaluationObservation.ObservedSource> sources) {
        return new EvaluationObservation("1.0", "case-1", sources, null, null, List.of(), Map.of(), 1L, null);
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
