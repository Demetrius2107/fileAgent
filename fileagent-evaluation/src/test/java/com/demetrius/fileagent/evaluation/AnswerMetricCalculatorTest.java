package com.demetrius.fileagent.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerMetricCalculatorTest {

    @Test
    void shouldEvaluateFactsCitationsDecisionAndExternalJudgeScores() {
        EvaluationCase evaluationCase = new EvaluationCase("1.0", "answer-1", "FACT", List.of(), "年假规则？",
                List.of(), new EvaluationCase.Filters("eval", "baseline", null),
                new EvaluationCase.Expected(true,
                        List.of(new EvaluationCase.ExpectedSource(null, null, "handbook.md", null, null, null, 3)),
                        List.of("年假5天", "提前3天申请"),
                        List.of("无需审批")));
        EvaluationObservation observation = new EvaluationObservation("1.0", "answer-1", List.of(),
                "员工享有年假 5 天，应提前 3 天申请。", false,
                List.of(
                        new EvaluationObservation.ObservedSource(null, null, "handbook.md", null, null,
                                null, null, null, null),
                        new EvaluationObservation.ObservedSource(null, null, "other.md", null, null,
                                null, null, null, null)),
                Map.of("faithfulness", 0.9), 10L, null);

        Map<String, Double> scores = new AnswerMetricCalculator().calculate(evaluationCase, observation);

        assertThat(scores).containsEntry("requiredFactCoverage", 1.0)
                .containsEntry("forbiddenFactSafety", 1.0)
                .containsEntry("citationPrecision", 0.5)
                .containsEntry("citationRecall", 1.0)
                .containsEntry("answerDecisionAccuracy", 1.0)
                .containsEntry("judge.faithfulness", 0.9);
    }

    @Test
    void shouldNotInventAnswerMetricsForRetrievalOnlyObservation() {
        EvaluationCase evaluationCase = new EvaluationCase("1.0", "answer-2", "FACT", List.of(), "问题",
                List.of(), new EvaluationCase.Filters(null, null, null),
                new EvaluationCase.Expected(true, List.of(), List.of("事实"), List.of()));
        EvaluationObservation observation = new EvaluationObservation("1.0", "answer-2", List.of(),
                null, null, List.of(), Map.of(), 2L, null);

        assertThat(new AnswerMetricCalculator().calculate(evaluationCase, observation)).isEmpty();
    }
}
