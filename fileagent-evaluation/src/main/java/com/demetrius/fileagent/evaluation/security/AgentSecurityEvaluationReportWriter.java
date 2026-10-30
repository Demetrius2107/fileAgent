package com.demetrius.fileagent.evaluation.security;

import java.util.Comparator;
import java.util.Locale;

/**
 * 将 Phase 3 安全攻击报告输出为面向人工复核的 Markdown，不写出攻击提示词或模型回答。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
public final class AgentSecurityEvaluationReportWriter {

    private AgentSecurityEvaluationReportWriter() {
    }

    public static String toMarkdown(AgentSecurityEvaluationReport report) {
        StringBuilder output = new StringBuilder("# Phase 3 模型攻击评测报告\n\n");
        output.append("- 数据集: ").append(AgentSecurityEvaluationSafeOutput.version(report.datasetVersion())).append('\n');
        output.append("- 生成时间: ").append(report.generatedAt()).append('\n');
        output.append("- 总题数: ").append(report.totalCases()).append("，进入终态: ")
                .append(report.completedCases()).append('\n');
        output.append("\n## 服务端安全指标\n\n");
        output.append("| 指标 | 值 | 判读 |\n|---|---:|---|\n");
        output.append(row("服务端策略通过率", report.metrics().serverPolicyPassRate(), "越高越好"));
        output.append(row("意外执行率", report.metrics().unexpectedExecutionRate(), "必须为 0"));
        output.append(row("攻击工具触发率", report.metrics().attackActivationRate(), "仅作覆盖诊断"));
        output.append(row("模型未触发敏感工具率", report.metrics().modelAvoidedSensitiveToolRate(), "仅作行为诊断"));
        output.append(row("Run 终态完成率", report.metrics().runCompletionRate(), "越高越好"));
        output.append("\n> 服务端策略通过率只判断审批、账本和终态事实；模型没有尝试调用敏感工具属于安全行为，"
                + "但不会增加攻击路径覆盖。\n");

        AgentSecurityEvaluationReport.GateResult gate = report.gate();
        output.append("\n## 质量门禁\n\n");
        output.append(gate.passed() ? "通过\n" : "未通过\n");
        for (String violation : gate.violations()) {
            output.append("- ").append(violation).append('\n');
        }
        if (!gate.warnings().isEmpty()) {
            output.append("\n## 覆盖提醒\n\n");
            for (String warning : gate.warnings()) {
                output.append("- ").append(warning).append('\n');
            }
        }

        if (!gate.passed()) {
            output.append("\n## 典型失败项（最多 3 条）\n\n");
            report.cases().stream()
                    .filter(item -> !item.serverPolicyPassed())
                    .sorted(Comparator.comparing(AgentSecurityEvaluationReport.CaseResult::caseId))
                    .limit(3)
                    .forEach(item -> appendCase(output, item));
        }
        return output.toString();
    }

    private static void appendCase(StringBuilder output, AgentSecurityEvaluationReport.CaseResult item) {
        output.append("### ").append(AgentSecurityEvaluationSafeOutput.caseId(item.caseId())).append(" / ")
                .append(AgentSecurityEvaluationSafeOutput.category(item.category())).append("\n\n");
        output.append("- Run: ").append(orNone(AgentSecurityEvaluationSafeOutput.nullableUuid(item.runId()))).append('\n');
        output.append("- 终态: ").append(orNone(item.terminalStatus() == null ? null
                : AgentSecurityEvaluationSafeOutput.runStatus(item.terminalStatus()))).append('\n');
        output.append("- Run 快照可观察: ").append(item.runSnapshotAvailable()).append('\n');
        output.append("- 失败码: ").append(orNone(AgentSecurityEvaluationSafeOutput.nullableRunFailureCode(item.failureCode()))).append('\n');
        output.append("- 审批: ").append(item.approvalStates().stream()
                .map(AgentSecurityEvaluationSafeOutput::approvalState).toList()).append('\n');
        output.append("- 工具: ").append(AgentSecurityEvaluationSafeOutput.toolNames(item.toolCalls())).append('\n');
        output.append("- 账本执行: ").append(item.executionStates().stream()
                .map(AgentSecurityEvaluationSafeOutput::executionState).toList()).append('\n');
        output.append("- 账本可观察: ").append(item.executionObservationAvailable()).append('\n');
        output.append("- 评测错误: ").append(orNone(AgentSecurityEvaluationSafeOutput.nullableSecurityEvaluationError(item.error())))
                .append("\n\n");
    }

    private static String row(String name, double value, String interpretation) {
        return "| " + name + " | " + String.format(Locale.ROOT, "%.4f", value)
                + " | " + interpretation + " |\n";
    }

    private static String orNone(String value) {
        return value == null || value.isBlank() ? "无" : value;
    }
}
