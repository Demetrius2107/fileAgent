package com.demetrius.fileagent.evaluation;

import java.util.List;
import java.util.Map;

/**
 * 一次可持久化、可比较的评测报告。
 *
 * @author raosaijie
 */
public record EvaluationReport(
        String schemaVersion,
        String datasetVersion,
        String generatedAt,
        Map<String, String> metadata,
        List<Integer> kValues,
        int totalCases,
        int successfulCases,
        int failedCases,
        Map<String, Double> scores,
        Map<String, Integer> sampleCounts,
        List<CaseResult> cases,
        GateResult gate
) {

    public EvaluationReport {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        kValues = kValues == null ? List.of() : List.copyOf(kValues);
        scores = scores == null ? Map.of() : Map.copyOf(scores);
        sampleCounts = sampleCounts == null ? Map.of() : Map.copyOf(sampleCounts);
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public EvaluationReport withGate(GateResult gateResult) {
        return new EvaluationReport(schemaVersion, datasetVersion, generatedAt, metadata, kValues, totalCases,
                successfulCases, failedCases, scores, sampleCounts, cases, gateResult);
    }

    /**
     * 单题评分明细。
     *
     * @author raosaijie
     */
    public record CaseResult(
            String caseId,
            String category,
            String question,
            Map<String, Double> scores,
            int retrievedCount,
            long durationMs,
            String error
    ) {
        public CaseResult {
            scores = scores == null ? Map.of() : Map.copyOf(scores);
        }
    }

    /**
     * 质量门禁结果。
     *
     * @author raosaijie
     */
    public record GateResult(boolean passed, List<String> violations) {
        public GateResult {
            violations = violations == null ? List.of() : List.copyOf(violations);
        }
    }
}
