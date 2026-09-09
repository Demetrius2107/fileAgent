package com.demetrius.fileagent.evaluation;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 汇总 Judge 回答指标并计算确定性的引用指标。
 *
 * @author raosaijie
 */
public final class AnswerMetricCalculator {

    private static final Set<String> BUILT_IN_JUDGE_METRICS = Set.of(
            "requiredFactCoverage", "forbiddenFactSafety", "answerDecisionAccuracy", "unsupportedClaimSafety");

    public Map<String, Double> calculate(EvaluationCase evaluationCase, EvaluationObservation observation) {
        if (!hasAnswerObservation(observation)) {
            return Map.of();
        }

        Map<String, Double> scores = new LinkedHashMap<>();
        addCitationScores(scores, evaluationCase.expected().relevantSources(), observation.citations());
        observation.judgeScores().forEach((name, value) -> scores.put(
                BUILT_IN_JUDGE_METRICS.contains(name) ? name : "judge." + name,
                validateJudgeScore(name, value)));
        return scores;
    }

    private static boolean hasAnswerObservation(EvaluationObservation observation) {
        return observation.answer() != null || observation.refused() != null
                || !observation.citations().isEmpty() || !observation.judgeScores().isEmpty();
    }

    private static void addCitationScores(Map<String, Double> scores,
                                          List<EvaluationCase.ExpectedSource> expected,
                                          List<EvaluationObservation.ObservedSource> citations) {
        List<EvaluationCase.ExpectedSource> expectedCitations = expected.stream()
                .collect(java.util.stream.Collectors.toMap(
                        EvaluationCase.ExpectedSource::citationIdentity,
                        source -> source,
                        (first, ignored) -> first,
                        LinkedHashMap::new))
                .values().stream().toList();
        if (expectedCitations.isEmpty() && citations.isEmpty()) {
            return;
        }
        long correctCitations = citations.stream()
                .filter(citation -> expectedCitations.stream().anyMatch(source -> source.matchesCitation(citation)))
                .count();
        scores.put("citationPrecision", citations.isEmpty() ? 0.0 : correctCitations / (double) citations.size());

        Set<Integer> matched = new HashSet<>();
        for (EvaluationObservation.ObservedSource citation : citations) {
            for (int i = 0; i < expectedCitations.size(); i++) {
                if (!matched.contains(i) && expectedCitations.get(i).matchesCitation(citation)) {
                    matched.add(i);
                    break;
                }
            }
        }
        scores.put("citationRecall", expectedCitations.isEmpty()
                ? 1.0 : matched.size() / (double) expectedCitations.size());
    }

    private static double validateJudgeScore(String name, Double value) {
        if (value == null || !Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("judgeScores." + name + " 必须在 0 到 1 之间");
        }
        return value;
    }

}
