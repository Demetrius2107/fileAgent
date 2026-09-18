package com.demetrius.fileagent.evaluation;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Set;

/**
 * 把 Agent 评测报告渲染为可读的 Markdown。
 */
public final class AgentEvaluationReportWriter {

    private AgentEvaluationReportWriter() {
    }

    public static String toMarkdown(AgentEvaluationReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Agent 评测报告\n\n");
        sb.append("- 数据集: ").append(report.datasetVersion()).append('\n');
        sb.append("- 生成时间: ").append(report.generatedAt()).append('\n');
        sb.append("- 总题数: ").append(report.totalCases())
                .append("，成功 ").append(report.successfulCases())
                .append("，失败 ").append(report.failedCases()).append('\n');

        AgentEvaluationReport.AnswerMetrics answer = report.answerMetrics();
        sb.append("\n## 答案质量（Judge）\n\n");
        sb.append("| 指标 | 值 |\n|---|---|\n");
        sb.append(row("回答决策正确率", answer.answerDecisionAccuracy()));
        sb.append(row("必答要点覆盖率", answer.requiredFactCoverage()));
        sb.append(row("禁答内容安全性", answer.forbiddenFactSafety()));
        sb.append(row("无依据主张安全性", answer.unsupportedClaimSafety()));

        AgentEvaluationReport.AgentMetrics agent = report.agentMetrics();
        sb.append("\n## Agent 行为\n\n");
        sb.append("| 指标 | 值 |\n|---|---|\n");
        sb.append(row("运行成功率", agent.runSuccessRate()));
        sb.append(row("预算遵守率", agent.budgetComplianceRate()));
        sb.append(row("工具白名单通过率", agent.toolWhitelistPassRate()));
        sb.append(row("引用覆盖率", agent.citationCoverageRate()));
        sb.append(row("引用有效性", agent.citationValidityRate()));
        sb.append(row("拒答正确率", agent.refusalDecisionAccuracy()));
        sb.append("| 平均步骤数 | ").append(format(agent.avgStepCount())).append(" |\n");
        sb.append("| 平均耗时(ms) | ").append(format(agent.avgDurationMs())).append(" |\n");
        sb.append("\n> 运行成功率只统计终态为 `SUCCEEDED` 且无评测错误的题；引用覆盖率只统计成功的"
                + " `KNOWLEDGE_BASED` 题，引用有效性只统计其中实际标注了来源的题。"
                + " `GENERAL_KNOWLEDGE`、`REFUSE` 和失败 Run 的引用状态为不适用。\n");

        if (report.gate() != null) {
            sb.append("\n## 质量门禁\n\n");
            sb.append(report.gate().passed() ? "通过 ✅\n" : "未通过 ❌\n");
            for (String violation : report.gate().violations()) {
                sb.append("- ").append(violation).append('\n');
            }
            if (!report.gate().passed()) {
                appendRepresentativeCases(sb, report);
            }
        }
        return sb.toString();
    }

    private static void appendRepresentativeCases(StringBuilder sb, AgentEvaluationReport report) {
        Set<String> violatedMetrics = report.gate().violations().stream()
                .map(AgentEvaluationReportWriter::metricName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<AgentEvaluationReport.CaseResult> cases = report.cases().stream()
                .filter(c -> violatedMetrics.stream().anyMatch(metric -> metricValue(metric, c) < 1.0))
                .sorted(Comparator.comparingDouble(c -> lowestMetricValue(violatedMetrics, c)))
                .limit(3)
                .toList();
        if (cases.isEmpty()) {
            return;
        }
        sb.append("\n## 典型失败题（最多 3 道）\n\n");
        for (AgentEvaluationReport.CaseResult c : cases) {
            sb.append("### ").append(c.caseId()).append(" / ").append(c.category()).append("\n\n")
                    .append("- 问题：").append(c.question()).append('\n')
                    .append("- 终态：").append(c.terminalStatus()).append('\n')
                    .append("- 失败码：").append(c.failureCode() == null ? "无" : c.failureCode()).append('\n')
                    .append("- 引用状态：").append(c.citationStatus().displayName()).append('\n')
                    .append("- 检索文件：").append(c.retrievedFilenames()).append('\n')
                    .append("- 引用文件：").append(c.citedFilenames()).append('\n')
                    .append("- 回答：").append(c.answerText() == null ? "" : c.answerText()).append('\n');
            if (c.error() != null) {
                sb.append("- 评测错误：").append(c.error()).append('\n');
            }
            sb.append('\n');
        }
    }

    private static String metricName(String violation) {
        int end = 0;
        while (end < violation.length()
                && violation.charAt(end) != '='
                && violation.charAt(end) != ' ') {
            end++;
        }
        return violation.substring(0, end);
    }

    private static double lowestMetricValue(Set<String> metrics,
                                            AgentEvaluationReport.CaseResult result) {
        return metrics.stream()
                .mapToDouble(metric -> metricValue(metric, result))
                .min()
                .orElse(1.0);
    }

    private static double metricValue(String metric, AgentEvaluationReport.CaseResult result) {
        return switch (metric) {
            case "answer.answerDecisionAccuracy" -> result.answer().answerDecisionAccuracy();
            case "answer.requiredFactCoverage" -> result.answer().requiredFactCoverage();
            case "answer.forbiddenFactSafety" -> result.answer().forbiddenFactSafety();
            case "answer.unsupportedClaimSafety" -> result.answer().unsupportedClaimSafety();
            case "agent.runSuccessRate" -> result.agent().runSuccessRate();
            case "agent.budgetComplianceRate" -> result.agent().budgetComplianceRate();
            case "agent.toolWhitelistPassRate" -> result.agent().toolWhitelistPassRate();
            case "agent.citationCoverageRate" -> result.agent().citationCoverageRate();
            case "agent.citationValidityRate" -> result.agent().citationValidityRate();
            case "agent.refusalDecisionAccuracy" -> result.agent().refusalDecisionAccuracy();
            default -> 1.0;
        };
    }

    private static String row(String name, double value) {
        return "| " + name + " | " + format(value) + " |\n";
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
