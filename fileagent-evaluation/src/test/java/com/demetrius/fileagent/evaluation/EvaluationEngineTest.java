package com.demetrius.fileagent.evaluation;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationEngineTest {

    private final EvaluationEngine engine = new EvaluationEngine(
            Clock.fixed(Instant.parse("2026-09-07T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void shouldAggregateOnlyAvailableMetricsAndExposeSampleCounts() {
        EvaluationCase relevant = evaluationCase("relevant", true,
                List.of(new EvaluationCase.ExpectedSource(null, null, "policy.md", null, null, null, 3)));
        EvaluationCase noAnswer = evaluationCase("no-answer", false, List.of());
        EvaluationObservation hit = observation("relevant", List.of(observed("policy.md")), null);
        EvaluationObservation empty = observation("no-answer", List.of(), null);

        EvaluationReport report = engine.evaluate("v1", List.of(relevant, noAnswer), List.of(hit, empty),
                List.of(1, 5), Map.of("gitCommit", "abc"));

        assertThat(report.generatedAt()).isEqualTo("2026-09-07T10:00:00Z");
        assertThat(report.totalCases()).isEqualTo(2);
        assertThat(report.successfulCases()).isEqualTo(2);
        assertThat(report.failedCases()).isZero();
        assertThat(report.scores()).containsEntry("recall@1", 1.0)
                .containsEntry("noAnswerEmptyRetrieval", 1.0);
        assertThat(report.sampleCounts()).containsEntry("recall@1", 1)
                .containsEntry("noAnswerEmptyRetrieval", 1);
    }

    @Test
    void shouldMarkMissingAndFailedObservationsWithoutSilentlyScoringThem() {
        EvaluationCase missing = evaluationCase("missing", true, List.of());
        EvaluationCase failed = evaluationCase("failed", true, List.of());
        EvaluationObservation failure = observation("failed", List.of(), "timeout");

        EvaluationReport report = engine.evaluate("v1", List.of(missing, failed), List.of(failure),
                List.of(5), Map.of());

        assertThat(report.successfulCases()).isZero();
        assertThat(report.failedCases()).isEqualTo(2);
        assertThat(report.scores()).containsOnly(Map.entry("caseSuccessRate", 0.0));
        assertThat(report.cases()).extracting(EvaluationReport.CaseResult::error)
                .containsExactly("缺少观测值", "timeout");
    }

    private static EvaluationCase evaluationCase(String id, boolean shouldAnswer,
                                                 List<EvaluationCase.ExpectedSource> sources) {
        return new EvaluationCase("1.0", id, "FACT", List.of(), "问题", List.of(),
                new EvaluationCase.Filters(null, null, null),
                new EvaluationCase.Expected(shouldAnswer, sources, List.of(), List.of()));
    }

    private static EvaluationObservation observation(String id,
                                                     List<EvaluationObservation.ObservedSource> sources,
                                                     String error) {
        return new EvaluationObservation("1.0", id, sources, null, null,
                List.of(), Map.of(), Map.of(), 3L, error);
    }

    private static EvaluationObservation.ObservedSource observed(String filename) {
        return new EvaluationObservation.ObservedSource(null, null, filename, null, null,
                null, null, null, 0.9);
    }
}
