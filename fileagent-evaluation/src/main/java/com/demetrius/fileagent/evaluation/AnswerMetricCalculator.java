package com.demetrius.fileagent.evaluation;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 计算可确定复现的回答事实与引用指标。
 *
 * @author raosaijie
 */
public final class AnswerMetricCalculator {

    public Map<String, Double> calculate(EvaluationCase evaluationCase, EvaluationObservation observation) {
        if (!hasAnswerObservation(observation)) {
            return Map.of();
        }

        Map<String, Double> scores = new LinkedHashMap<>();
        String normalizedAnswer = normalize(observation.answer());
        List<String> requiredFacts = evaluationCase.expected().requiredFacts();
        if (!requiredFacts.isEmpty()) {
            long found = requiredFacts.stream().map(AnswerMetricCalculator::normalize)
                    .filter(normalizedAnswer::contains).count();
            scores.put("requiredFactCoverage", found / (double) requiredFacts.size());
        }
        List<String> forbiddenFacts = evaluationCase.expected().forbiddenFacts();
        if (!forbiddenFacts.isEmpty()) {
            long found = forbiddenFacts.stream().map(AnswerMetricCalculator::normalize)
                    .filter(normalizedAnswer::contains).count();
            scores.put("forbiddenFactSafety", 1.0 - found / (double) forbiddenFacts.size());
        }
        if (observation.refused() != null) {
            boolean expectedRefusal = !evaluationCase.expected().shouldAnswer();
            scores.put("answerDecisionAccuracy", expectedRefusal == observation.refused() ? 1.0 : 0.0);
        }
        addCitationScores(scores, evaluationCase.expected().relevantSources(), observation.citations());
        observation.judgeScores().forEach((name, value) -> scores.put("judge." + name, validateJudgeScore(name, value)));
        return scores;
    }

    private static boolean hasAnswerObservation(EvaluationObservation observation) {
        return observation.answer() != null || observation.refused() != null
                || !observation.citations().isEmpty() || !observation.judgeScores().isEmpty();
    }

    private static void addCitationScores(Map<String, Double> scores,
                                          List<EvaluationCase.ExpectedSource> expected,
                                          List<EvaluationObservation.ObservedSource> citations) {
        if (expected.isEmpty() && citations.isEmpty()) {
            return;
        }
        long correctCitations = citations.stream()
                .filter(citation -> expected.stream().anyMatch(source -> source.matches(citation)))
                .count();
        scores.put("citationPrecision", citations.isEmpty() ? 0.0 : correctCitations / (double) citations.size());

        Set<Integer> matched = new HashSet<>();
        for (EvaluationObservation.ObservedSource citation : citations) {
            for (int i = 0; i < expected.size(); i++) {
                if (!matched.contains(i) && expected.get(i).matches(citation)) {
                    matched.add(i);
                    break;
                }
            }
        }
        scores.put("citationRecall", expected.isEmpty() ? 1.0 : matched.size() / (double) expected.size());
    }

    private static double validateJudgeScore(String name, Double value) {
        if (value == null || !Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("judgeScores." + name + " 必须在 0 到 1 之间");
        }
        return value;
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
