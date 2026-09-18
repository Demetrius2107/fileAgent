package com.demetrius.fileagent.evaluation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 质量门禁：复用 {@link QualityGateConfig} 的阈值/回退语义，把 Agent 报告的两类
 * 指标展开为带前缀的分数键（{@code answer.*} / {@code agent.*}）后执行最低分与回退检查。
 */
public final class AgentQualityGateEvaluator {

    public AgentEvaluationReport.GateResult evaluate(AgentEvaluationReport current,
                                                     QualityGateConfig config,
                                                     AgentEvaluationReport baseline) {
        Map<String, Double> scores = flatten(current);
        List<String> violations = new ArrayList<>();
        config.minimumScores().forEach((metric, minimum) -> {
            Double actual = scores.get(metric);
            if (actual == null) {
                violations.add(metric + " 缺少结果，最低要求 " + format(minimum));
            } else if (actual < minimum) {
                violations.add(metric + "=" + format(actual) + " 低于最低要求 " + format(minimum));
            }
        });

        if (baseline != null) {
            Map<String, Double> baselineScores = flatten(baseline);
            for (String metric : config.regressionMetrics()) {
                Double actual = scores.get(metric);
                Double previous = baselineScores.get(metric);
                if (actual == null || previous == null) {
                    violations.add(metric + " 无法与 baseline 比较");
                } else if (previous - actual > config.maximumRegression()) {
                    violations.add(metric + " 相比 baseline 回退 " + format(previous - actual)
                            + "，允许回退 " + format(config.maximumRegression()));
                }
            }
        }
        return new AgentEvaluationReport.GateResult(violations.isEmpty(), violations);
    }

    static Map<String, Double> flatten(AgentEvaluationReport report) {
        Map<String, Double> scores = new LinkedHashMap<>();
        AgentEvaluationReport.AnswerMetrics answer = report.answerMetrics();
        AgentEvaluationReport.AgentMetrics agent = report.agentMetrics();
        scores.put("answer.answerDecisionAccuracy", answer.answerDecisionAccuracy());
        scores.put("answer.requiredFactCoverage", answer.requiredFactCoverage());
        scores.put("answer.forbiddenFactSafety", answer.forbiddenFactSafety());
        scores.put("answer.unsupportedClaimSafety", answer.unsupportedClaimSafety());
        scores.put("agent.runSuccessRate", agent.runSuccessRate());
        scores.put("agent.budgetComplianceRate", agent.budgetComplianceRate());
        scores.put("agent.toolWhitelistPassRate", agent.toolWhitelistPassRate());
        scores.put("agent.citationCoverageRate", agent.citationCoverageRate());
        scores.put("agent.citationValidityRate", agent.citationValidityRate());
        scores.put("agent.refusalDecisionAccuracy", agent.refusalDecisionAccuracy());
        return scores;
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }
}
