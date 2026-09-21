package com.demetrius.fileagent.evaluation;

import com.demetrius.fileagent.api.enums.AgentRunStatus;

import java.util.List;

/**
 * Agent 评测报告：把「答案质量」（复用 Judge）与「Agent 行为」两类指标分开承载，
 * 便于独立追踪语义正确性与运行时受控性；自适应检索指标仅在
 * {@code adaptiveMetrics != null} 时出现（无自适应样本的报告为 null）。
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
        AdaptiveMetrics adaptiveMetrics,
        List<CaseResult> cases,
        GateResult gate
) {

    public AgentEvaluationReport {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    /** 兼容构造：无自适应检索指标的报告。 */
    public AgentEvaluationReport(String schemaVersion,
                                 String datasetVersion,
                                 String generatedAt,
                                 int totalCases,
                                 int successfulCases,
                                 int failedCases,
                                 AnswerMetrics answerMetrics,
                                 AgentMetrics agentMetrics,
                                 List<CaseResult> cases,
                                 GateResult gate) {
        this(schemaVersion, datasetVersion, generatedAt, totalCases, successfulCases, failedCases,
                answerMetrics, agentMetrics, null, cases, gate);
    }

    public AgentEvaluationReport withGate(GateResult gateResult) {
        return new AgentEvaluationReport(schemaVersion, datasetVersion, generatedAt, totalCases,
                successfulCases, failedCases, answerMetrics, agentMetrics, adaptiveMetrics,
                cases, gateResult);
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
            double runSuccessRate,
            double budgetComplianceRate,
            double toolWhitelistPassRate,
            double citationCoverageRate,
            double citationValidityRate,
            double refusalDecisionAccuracy,
            double avgStepCount,
            double avgDurationMs) {
    }

    /**
     * 自适应检索指标（Phase 2A）。分母均为参与相应口径的自适应样本：
     * {@code queryTypeAccuracy}/{@code unnecessaryRetrievalRate} 的分母是带人工标注的题
     * （前者看实际检索类型是否一致，后者只统计预期 NONE 的题，越低越好）；
     * {@code queryCountComplianceRate}/{@code strategyComplianceRate} 的分母是实际执行了
     * 结构化检索的 Run；{@code subQuestionCoverage} 的分母是其中带子问题标注的多跳/比较题。
     */
    public record AdaptiveMetrics(
            double queryTypeAccuracy,
            double unnecessaryRetrievalRate,
            double queryCountComplianceRate,
            double strategyComplianceRate,
            double subQuestionCoverage) {
    }

    /** 单题引用状态。 */
    public enum CitationStatus {
        PASSED("通过"),
        MISSING("未覆盖"),
        INVALID("无效"),
        NOT_APPLICABLE("不适用");

        private final String displayName;

        CitationStatus(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** 单题明细。{@code adaptive == null} 表示该 Run 未执行结构化检索，不参与自适应指标。 */
    public record CaseResult(
            String caseId,
            String category,
            String question,
            String answerText,
            List<String> retrievedFilenames,
            List<String> citedFilenames,
            AgentRunStatus terminalStatus,
            String failureCode,
            CitationStatus citationStatus,
            AnswerMetrics answer,
            AgentMetrics agent,
            AdaptiveMetrics adaptive,
            String error) {

        public CaseResult {
            retrievedFilenames = retrievedFilenames == null ? List.of() : List.copyOf(retrievedFilenames);
            citedFilenames = citedFilenames == null ? List.of() : List.copyOf(citedFilenames);
        }

        /** 兼容构造：无单题自适应指标。 */
        public CaseResult(String caseId,
                          String category,
                          String question,
                          String answerText,
                          List<String> retrievedFilenames,
                          List<String> citedFilenames,
                          AgentRunStatus terminalStatus,
                          String failureCode,
                          CitationStatus citationStatus,
                          AnswerMetrics answer,
                          AgentMetrics agent,
                          String error) {
            this(caseId, category, question, answerText, retrievedFilenames, citedFilenames,
                    terminalStatus, failureCode, citationStatus, answer, agent, null, error);
        }
    }

    /** 质量门禁结果。 */
    public record GateResult(boolean passed, List<String> violations) {
        public GateResult {
            violations = violations == null ? List.of() : List.copyOf(violations);
        }
    }
}
