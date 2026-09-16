package com.demetrius.fileagent.evaluation;

import java.util.List;

/**
 * Agent 评测报告：把「答案质量」（复用 Judge）与「Agent 行为」两类指标分开承载，
 * 便于独立追踪语义正确性与运行时受控性。
 */
public record AgentEvaluationReport(
        String schemaVersion,
        String datasetVersion,
        String generatedAt,
        int totalCases,
        int successfulCases,
        int failedCases,
        AnswerMetrics answerMetrics,
        AgentMetrics agentMetrics,
        List<CaseResult> cases,
        GateResult gate
) {

    public AgentEvaluationReport {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public AgentEvaluationReport withGate(GateResult gateResult) {
        return new AgentEvaluationReport(schemaVersion, datasetVersion, generatedAt, totalCases,
                successfulCases, failedCases, answerMetrics, agentMetrics, cases, gateResult);
    }

    /** 答案质量指标（复用 Judge 语义评判）。 */
    public record AnswerMetrics(
            double answerDecisionAccuracy,
            double requiredFactCoverage,
            double forbiddenFactSafety,
            double unsupportedClaimSafety) {
    }

    /** Agent 行为指标（受控运行时行为）。 */
    public record AgentMetrics(
            double budgetComplianceRate,
            double toolWhitelistPassRate,
            double citationOnlyFromRetrievedRate,
            double refusalDecisionAccuracy,
            double avgStepCount,
            double avgDurationMs) {
    }

    /** 单题明细。 */
    public record CaseResult(
            String caseId,
            String category,
            String question,
            AnswerMetrics answer,
            AgentMetrics agent,
            String error) {
    }

    /** 质量门禁结果。 */
    public record GateResult(boolean passed, List<String> violations) {
        public GateResult {
            violations = violations == null ? List.of() : List.copyOf(violations);
        }
    }
}
