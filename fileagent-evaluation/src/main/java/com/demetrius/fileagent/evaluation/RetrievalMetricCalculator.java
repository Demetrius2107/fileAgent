package com.demetrius.fileagent.evaluation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计算单题检索排序指标。
 *
 * @author raosaijie
 */
public final class RetrievalMetricCalculator {

    private final List<Integer> kValues;

    public RetrievalMetricCalculator(List<Integer> kValues) {
        if (kValues == null || kValues.isEmpty() || kValues.stream().anyMatch(k -> k == null || k <= 0)) {
            throw new IllegalArgumentException("kValues 必须包含正整数");
        }
        this.kValues = kValues.stream().distinct().sorted().toList();
    }

    public Map<String, Double> calculate(EvaluationCase evaluationCase, EvaluationObservation observation) {
        List<EvaluationCase.ExpectedSource> expected = evaluationCase.expected().relevantSources();
        List<EvaluationObservation.ObservedSource> actual = observation.retrieved();
        Map<String, Double> scores = new LinkedHashMap<>();
        if (expected.isEmpty()) {
            scores.put("noAnswerEmptyRetrieval", actual.isEmpty() ? 1.0 : 0.0);
            return scores;
        }

        scores.put("mrr", reciprocalRank(expected, actual));
        for (int k : kValues) {
            RankingAtK ranking = rankAtK(expected, actual, k);
            scores.put("hitRate@" + k, ranking.matchedCount() > 0 ? 1.0 : 0.0);
            scores.put("recall@" + k, ranking.matchedCount() / (double) expected.size());
            int returned = Math.min(k, actual.size());
            scores.put("precision@" + k, returned == 0 ? 0.0 : ranking.matchedCount() / (double) returned);
            scores.put("ndcg@" + k, normalizedDiscountedGain(expected, ranking.gains(), k));
        }
        return scores;
    }

    private static double reciprocalRank(List<EvaluationCase.ExpectedSource> expected,
                                         List<EvaluationObservation.ObservedSource> actual) {
        for (int i = 0; i < actual.size(); i++) {
            if (findMatch(expected, actual.get(i), Set.of()) >= 0) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    private static RankingAtK rankAtK(List<EvaluationCase.ExpectedSource> expected,
                                      List<EvaluationObservation.ObservedSource> actual,
                                      int k) {
        Set<Integer> matchedExpected = new HashSet<>();
        List<Integer> gains = new ArrayList<>();
        int limit = Math.min(k, actual.size());
        for (int i = 0; i < limit; i++) {
            int expectedIndex = findMatch(expected, actual.get(i), matchedExpected);
            if (expectedIndex >= 0) {
                matchedExpected.add(expectedIndex);
                gains.add(expected.get(expectedIndex).relevance());
            } else {
                gains.add(0);
            }
        }
        return new RankingAtK(matchedExpected.size(), gains);
    }

    private static int findMatch(List<EvaluationCase.ExpectedSource> expected,
                                 EvaluationObservation.ObservedSource actual,
                                 Set<Integer> excludedIndexes) {
        int matchedIndex = -1;
        int highestSpecificity = -1;
        for (int i = 0; i < expected.size(); i++) {
            EvaluationCase.ExpectedSource source = expected.get(i);
            if (!excludedIndexes.contains(i) && source.matches(actual)
                    && source.specificity() > highestSpecificity) {
                matchedIndex = i;
                highestSpecificity = source.specificity();
            }
        }
        return matchedIndex;
    }

    private static double normalizedDiscountedGain(List<EvaluationCase.ExpectedSource> expected,
                                                   List<Integer> actualRelevance,
                                                   int k) {
        double dcg = discountedGain(actualRelevance);
        List<Integer> ideal = expected.stream()
                .map(EvaluationCase.ExpectedSource::relevance)
                .sorted(java.util.Comparator.reverseOrder())
                .limit(k)
                .toList();
        double idealDcg = discountedGain(ideal);
        return idealDcg == 0.0 ? 0.0 : dcg / idealDcg;
    }

    private static double discountedGain(List<Integer> relevanceValues) {
        double total = 0.0;
        for (int i = 0; i < relevanceValues.size(); i++) {
            double gain = Math.pow(2.0, relevanceValues.get(i)) - 1.0;
            total += gain / (Math.log(i + 2.0) / Math.log(2.0));
        }
        return total;
    }

    private record RankingAtK(int matchedCount, List<Integer> gains) {
    }
}
