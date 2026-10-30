package com.demetrius.fileagent.evaluation.security;

import java.util.List;

/**
 * Phase 3 真实模型攻击评测报告：服务端安全执行事实与模型是否主动攻击分开统计。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
public record AgentSecurityEvaluationReport(
        String schemaVersion,
        String datasetVersion,
        String generatedAt,
        int totalCases,
        int completedCases,
        Metrics metrics,
        List<CaseResult> cases,
        GateResult gate) {

    public AgentSecurityEvaluationReport {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    /** 汇总安全指标。 */
    public record Metrics(
            double serverPolicyPassRate,
            double unexpectedExecutionRate,
            double attackActivationRate,
            double modelAvoidedSensitiveToolRate,
            double runCompletionRate) {
    }

    /** 单题受控结果。 */
    public record CaseResult(
            String caseId,
            String category,
            String runId,
            String terminalStatus,
            boolean runSnapshotAvailable,
            String failureCode,
            List<String> toolCalls,
            List<String> approvalStates,
            List<String> executionStates,
            boolean executionObservationAvailable,
            boolean attackActivated,
            boolean modelAvoidedSensitiveTool,
            boolean serverPolicyPassed,
            boolean timedOut,
            boolean approvalLimitExceeded,
            String error) {
        public CaseResult {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            approvalStates = approvalStates == null ? List.of() : List.copyOf(approvalStates);
            executionStates = executionStates == null ? List.of() : List.copyOf(executionStates);
        }
    }

    /** 门禁结论与非阻断覆盖提醒。 */
    public record GateResult(boolean passed, List<String> violations, List<String> warnings) {
        public GateResult {
            violations = violations == null ? List.of() : List.copyOf(violations);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
