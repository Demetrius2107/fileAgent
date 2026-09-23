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
        sb.append(answer.forbiddenFactSafety() == null
                ? "| 禁答内容安全性 | 不适用（无禁答事实样本） |\n"
                : row("禁答内容安全性", answer.forbiddenFactSafety()));
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
        appendMetricScope(sb, report);

        if (report.adaptiveMetrics() != null) {
            AgentEvaluationReport.AdaptiveMetrics adaptive = report.adaptiveMetrics();
            sb.append("\n## 自适应检索\n\n");
            sb.append("| 指标 | 值 |\n|---|---|\n");
            sb.append(row("查询类型准确率", adaptive.queryTypeAccuracy()));
            sb.append(row("不必要检索率", adaptive.unnecessaryRetrievalRate()));
            sb.append(row("子查询数量合规率", adaptive.queryCountComplianceRate()));
            sb.append(row("策略合规率", adaptive.strategyComplianceRate()));
            sb.append(row("子问题覆盖率", adaptive.subQuestionCoverage()));
        }

        if (report.contextMetrics() != null) {
            AgentEvaluationReport.ContextMetrics context = report.contextMetrics();
            sb.append("\n## 上下文与预算\n\n");
            sb.append("| 指标 | 值 | 判读 |\n|---|---:|---|\n");
            sb.append(contextRow("当前问题保留率", context.promptPreservationRate(), "越高越好"));
            sb.append(contextRow("工具结果预算合规率", context.toolBudgetComplianceRate(), "越高越好"));
            sb.append(contextRow("预算耗尽后正常完成率", context.budgetExhaustionCompletionRate(), "越高越好"));
            sb.append(contextRow("历史摘要无依据主张率", context.summaryUnsupportedClaimRate(), "越低越好"));
            sb.append(contextRow("通用知识假引用率", context.fakeCitationRate(), "越低越好"));
            sb.append(contextRow("历史必答事实覆盖率", context.historyRequiredFactCoverage(), "越高越好"));
        }

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

    private static void appendMetricScope(StringBuilder sb, AgentEvaluationReport report) {
        long coverageDenominator = report.cases().stream()
                .filter(c -> c.citationStatus() != AgentEvaluationReport.CitationStatus.NOT_APPLICABLE)
                .count();
        long validityDenominator = report.cases().stream()
                .filter(c -> c.citationStatus() == AgentEvaluationReport.CitationStatus.PASSED
                        || c.citationStatus() == AgentEvaluationReport.CitationStatus.INVALID)
                .count();
        sb.append("\n> 运行成功率只统计终态为 `SUCCEEDED` 且无评测错误的题；引用覆盖率分母是成功的"
                + " `KNOWLEDGE_BASED` 题（").append(coverageDenominator).append(" 道），"
                + "引用有效性分母是其中实际标注了来源的题（").append(validityDenominator).append(" 道").append('）');
        if (validityDenominator == 0) {
            sb.append("，无已标注引用样本");
        }
        sb.append("。`GENERAL_KNOWLEDGE`、`REFUSE` 和失败 Run 的引用状态为不适用。\n");
        if (report.adaptiveMetrics() != null) {
            long adaptiveDenominator = report.cases().stream()
                    .filter(c -> c.adaptive() != null)
                    .count();
            sb.append("\n> 实际执行了结构化检索的 Run 有 ")
                    .append(adaptiveDenominator).append(" 道；类型准确率统计全部标注了预期类型的题（未检索视为 NONE），"
                    + "不必要检索率只统计预期 NONE 的题，越低越好；计划数量与策略合规率只统计实际检索的题。"
                    + "查询类型按首轮计划计算，轮数与策略检查所有轮次；"
                    + "子问题覆盖率仅为数量代理，多跳累计各轮计划数，其余只看首轮，不代表语义覆盖。\n");
        }
    }

    private static void appendRepresentativeCases(StringBuilder sb, AgentEvaluationReport report) {
        Set<String> violatedMetrics = report.gate().violations().stream()
                .map(AgentEvaluationReportWriter::metricName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> maximumMetrics = report.gate().violations().stream()
                .filter(violation -> violation.contains("高于最高要求"))
                .map(AgentEvaluationReportWriter::metricName)
                .collect(java.util.stream.Collectors.toSet());
        List<AgentEvaluationReport.CaseResult> cases = report.cases().stream()
                .filter(c -> violatedMetrics.stream().anyMatch(metric ->
                        maximumMetrics.contains(metric)
                                ? metricValue(metric, c) > 0.0
                                : metricValue(metric, c) < 1.0))
                .sorted(Comparator.comparingDouble(c -> lowestMetricValue(violatedMetrics, maximumMetrics, c)))
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
                                            Set<String> maximumMetrics,
                                            AgentEvaluationReport.CaseResult result) {
        return metrics.stream()
                .mapToDouble(metric -> maximumMetrics.contains(metric)
                        ? 1.0 - metricValue(metric, result) : metricValue(metric, result))
                .min()
                .orElse(1.0);
    }

    private static double metricValue(String metric, AgentEvaluationReport.CaseResult result) {
        return switch (metric) {
            case "answer.answerDecisionAccuracy" -> result.answer().answerDecisionAccuracy();
            case "answer.requiredFactCoverage" -> result.answer().requiredFactCoverage();
            case "answer.forbiddenFactSafety" -> result.answer().forbiddenFactSafety() == null
                    ? 1.0 : result.answer().forbiddenFactSafety();
            case "answer.unsupportedClaimSafety" -> result.answer().unsupportedClaimSafety();
            case "agent.runSuccessRate" -> result.agent().runSuccessRate();
            case "agent.budgetComplianceRate" -> result.agent().budgetComplianceRate();
            case "agent.toolWhitelistPassRate" -> result.agent().toolWhitelistPassRate();
            case "agent.citationCoverageRate" -> result.agent().citationCoverageRate();
            case "agent.citationValidityRate" -> result.agent().citationValidityRate();
            case "agent.refusalDecisionAccuracy" -> result.agent().refusalDecisionAccuracy();
            case "adaptive.queryTypeAccuracy" -> result.adaptive() == null
                    ? 1.0 : result.adaptive().queryTypeAccuracy();
            case "adaptive.unnecessaryRetrievalRate" -> result.adaptive() == null
                    ? 1.0 : result.adaptive().unnecessaryRetrievalRate();
            case "adaptive.queryCountComplianceRate" -> result.adaptive() == null
                    ? 1.0 : result.adaptive().queryCountComplianceRate();
            case "adaptive.strategyComplianceRate" -> result.adaptive() == null
                    ? 1.0 : result.adaptive().strategyComplianceRate();
            case "adaptive.subQuestionCoverage" -> result.adaptive() == null
                    ? 1.0 : result.adaptive().subQuestionCoverage();
            case "context.promptPreservationRate" -> contextValue(result, c -> c.promptPreservationRate());
            case "context.toolBudgetComplianceRate" -> contextValue(result, c -> c.toolBudgetComplianceRate());
            case "context.budgetExhaustionCompletionRate" -> contextValue(result, c -> c.budgetExhaustionCompletionRate());
            case "context.summaryUnsupportedClaimRate" -> contextValue(result, c -> c.summaryUnsupportedClaimRate());
            case "context.fakeCitationRate" -> contextValue(result, c -> c.fakeCitationRate());
            case "context.historyRequiredFactCoverage" -> contextValue(result, c -> c.historyRequiredFactCoverage());
            default -> 1.0;
        };
    }

    private static double contextValue(AgentEvaluationReport.CaseResult result,
                                       java.util.function.ToDoubleFunction<AgentEvaluationReport.ContextMetrics> value) {
        return result.context() == null ? 1.0 : value.applyAsDouble(result.context());
    }

    private static String contextRow(String name, double value, String interpretation) {
        return "| " + name + " | " + format(value) + " | " + interpretation + " |\n";
    }

    private static String row(String name, double value) {
        return "| " + name + " | " + format(value) + " |\n";
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
