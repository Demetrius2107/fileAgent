package com.demetrius.fileagent.evaluation;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 合并单题指标并生成整套数据集的宏平均结果。
 *
 * @author raosaijie
 */
public final class EvaluationEngine {

    private final Clock clock;

    public EvaluationEngine() {
        this(Clock.systemUTC());
    }

    EvaluationEngine(Clock clock) {
        this.clock = clock;
    }

    public EvaluationReport evaluate(String datasetVersion,
                                     List<EvaluationCase> cases,
                                     List<EvaluationObservation> observations,
                                     List<Integer> kValues,
                                     Map<String, String> metadata) {
        Map<String, EvaluationObservation> observationsById = observations.stream()
                .collect(Collectors.toMap(EvaluationObservation::caseId, Function.identity()));
        Set<String> caseIds = cases.stream().map(EvaluationCase::id).collect(Collectors.toSet());
        List<String> unknownIds = observationsById.keySet().stream().filter(id -> !caseIds.contains(id)).sorted().toList();
        if (!unknownIds.isEmpty()) {
            throw new IllegalArgumentException("观测值引用了不存在的评测题: " + unknownIds);
        }

        RetrievalMetricCalculator retrievalCalculator = new RetrievalMetricCalculator(kValues);
        AnswerMetricCalculator answerCalculator = new AnswerMetricCalculator();
        Map<String, Double> totals = new LinkedHashMap<>();
        Map<String, Integer> sampleCounts = new LinkedHashMap<>();
        List<EvaluationReport.CaseResult> caseResults = new ArrayList<>();
        int successfulCases = 0;

        for (EvaluationCase evaluationCase : cases) {
            EvaluationObservation observation = observationsById.get(evaluationCase.id());
            if (observation == null) {
                caseResults.add(failedResult(evaluationCase, "缺少观测值"));
                continue;
            }
            if (observation.error() != null && !observation.error().isBlank()) {
                caseResults.add(new EvaluationReport.CaseResult(evaluationCase.id(), evaluationCase.category(),
                        evaluationCase.question(), Map.of(), observation.retrieved().size(),
                        observation.durationMs(), observation.error()));
                continue;
            }

            Map<String, Double> scores = new LinkedHashMap<>(retrievalCalculator.calculate(evaluationCase, observation));
            scores.putAll(answerCalculator.calculate(evaluationCase, observation));
            scores.forEach((metric, value) -> {
                totals.merge(metric, value, Double::sum);
                sampleCounts.merge(metric, 1, Integer::sum);
            });
            caseResults.add(new EvaluationReport.CaseResult(evaluationCase.id(), evaluationCase.category(),
                    evaluationCase.question(), scores, observation.retrieved().size(),
                    observation.durationMs(), null));
            successfulCases++;
        }

        Map<String, Double> averages = new LinkedHashMap<>();
        totals.forEach((metric, total) -> averages.put(metric, total / sampleCounts.get(metric)));
        averages.put("caseSuccessRate", cases.isEmpty() ? 0.0 : successfulCases / (double) cases.size());
        sampleCounts.put("caseSuccessRate", cases.size());
        return new EvaluationReport("1.0", datasetVersion, Instant.now(clock).toString(), metadata, kValues,
                cases.size(), successfulCases, cases.size() - successfulCases,
                averages, sampleCounts, caseResults, null);
    }

    private static EvaluationReport.CaseResult failedResult(EvaluationCase evaluationCase, String error) {
        return new EvaluationReport.CaseResult(evaluationCase.id(), evaluationCase.category(),
                evaluationCase.question(), Map.of(), 0, 0L, error);
    }
}
