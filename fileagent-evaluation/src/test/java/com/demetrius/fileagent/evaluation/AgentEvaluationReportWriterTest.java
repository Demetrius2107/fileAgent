package com.demetrius.fileagent.evaluation;

import com.demetrius.fileagent.api.enums.AgentRunStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent Markdown 报告渲染测试。
 *
 * @author raosaijie
 */
class AgentEvaluationReportWriterTest {

    @Test
    void shouldRenderOnlyRepresentativeCasesForGateFailures() {
        AgentEvaluationReport report = report(
                new AgentEvaluationReport.CaseResult(
                        "case-bad", "KNOWLEDGE_BASED", "员工年假是多少？", "5 天",
                        List.of("employee-handbook.md"), List.of(), AgentRunStatus.SUCCEEDED, null,
                        AgentEvaluationReport.CitationStatus.MISSING,
                        new AgentEvaluationReport.AnswerMetrics(1, 1, 1, 1),
                        new AgentEvaluationReport.AgentMetrics(1, 1, 1, 0, 1, 1, 1, 10), null),
                new AgentEvaluationReport.CaseResult(
                        "case-good", "KNOWLEDGE_BASED", "如何申请年假？", "提交申请 [来源：employee-handbook.md]",
                        List.of("employee-handbook.md"), List.of("employee-handbook.md"), AgentRunStatus.SUCCEEDED,
                        null, AgentEvaluationReport.CitationStatus.PASSED,
                        new AgentEvaluationReport.AnswerMetrics(1, 1, 1, 1),
                        new AgentEvaluationReport.AgentMetrics(1, 1, 1, 1, 1, 1, 1, 10), null));

        String markdown = AgentEvaluationReportWriter.toMarkdown(report.withGate(
                new AgentEvaluationReport.GateResult(false,
                        List.of("agent.citationCoverageRate=0.5000 低于最低要求 0.9000"))));

        assertThat(markdown).contains("## 典型失败题");
        assertThat(markdown).contains("case-bad");
        assertThat(markdown).contains("未覆盖");
        assertThat(markdown).doesNotContain("case-good");
    }

    @Test
    void shouldNotRenderCaseDetailsWhenGatePasses() {
        AgentEvaluationReport report = report(new AgentEvaluationReport.CaseResult(
                "case-good", "GENERAL_KNOWLEDGE", "HTTP 404 是什么意思？", "资源未找到",
                List.of(), List.of(), AgentRunStatus.SUCCEEDED, null,
                AgentEvaluationReport.CitationStatus.NOT_APPLICABLE,
                new AgentEvaluationReport.AnswerMetrics(1, 1, 1, 1),
                new AgentEvaluationReport.AgentMetrics(1, 1, 1, 0, 1, 1, 1, 10), null));

        String markdown = AgentEvaluationReportWriter.toMarkdown(report.withGate(
                new AgentEvaluationReport.GateResult(true, List.of())));

        assertThat(markdown).contains("通过 ✅");
        assertThat(markdown).doesNotContain("## 典型失败题");
        assertThat(markdown).doesNotContain("case-good");
    }

    private AgentEvaluationReport report(AgentEvaluationReport.CaseResult... cases) {
        return new AgentEvaluationReport("1.0", "agent-v1", "now", cases.length, cases.length, 0,
                new AgentEvaluationReport.AnswerMetrics(1, 1, 1, 1),
                new AgentEvaluationReport.AgentMetrics(1, 1, 1, 0.5, 1, 1, 1, 10),
                List.of(cases), null);
    }
}
