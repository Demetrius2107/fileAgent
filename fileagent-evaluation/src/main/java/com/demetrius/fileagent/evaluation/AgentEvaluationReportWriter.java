package com.demetrius.fileagent.evaluation;

import java.util.Locale;

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
        sb.append(row("预算遵守率", agent.budgetComplianceRate()));
        sb.append(row("工具白名单通过率", agent.toolWhitelistPassRate()));
        sb.append(row("引用仅来自检索比率", agent.citationOnlyFromRetrievedRate()));
        sb.append(row("拒答正确率", agent.refusalDecisionAccuracy()));
        sb.append("| 平均步骤数 | ").append(format(agent.avgStepCount())).append(" |\n");
        sb.append("| 平均耗时(ms) | ").append(format(agent.avgDurationMs())).append(" |\n");
        sb.append("\n> 引用指标只校验回答中实际标出的来源是否来自本次检索；"
                + "`GENERAL_KNOWLEDGE` 题允许无检索、无引用，不会因此判错。\n");

        if (report.gate() != null) {
            sb.append("\n## 质量门禁\n\n");
            sb.append(report.gate().passed() ? "通过 ✅\n" : "未通过 ❌\n");
            for (String violation : report.gate().violations()) {
                sb.append("- ").append(violation).append('\n');
            }
        }

        sb.append("\n## 单题明细\n\n");
        sb.append("| 题目 | 分类 | 决策 | 预算 | 白名单 | 引用 | 错误 |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (AgentEvaluationReport.CaseResult c : report.cases()) {
            sb.append("| ").append(c.caseId()).append(" | ").append(c.category())
                    .append(" | ").append(format(c.answer().answerDecisionAccuracy()))
                    .append(" | ").append(format(c.agent().budgetComplianceRate()))
                    .append(" | ").append(format(c.agent().toolWhitelistPassRate()))
                    .append(" | ").append(format(c.agent().citationOnlyFromRetrievedRate()))
                    .append(" | ").append(c.error() == null ? "" : c.error()).append(" |\n");
        }
        return sb.toString();
    }

    private static String row(String name, double value) {
        return "| " + name + " | " + format(value) + " |\n";
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
