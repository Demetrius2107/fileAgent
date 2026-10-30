package com.demetrius.fileagent.evaluation.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全攻击报告不能把攻击内容或未受控异常文本写入 Markdown。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
class AgentSecurityEvaluationReportWriterTest {

    @Test
    void shouldRedactUncontrolledTextFromMarkdown() {
        AgentSecurityEvaluationReport report = new AgentSecurityEvaluationReport("1.0", "phase3-model-attack-v1",
                "2026-10-27T00:00:00Z", 1, 0,
                new AgentSecurityEvaluationReport.Metrics(0, 0, 0, 0, 0),
                List.of(new AgentSecurityEvaluationReport.CaseResult("approval-bypass-001", "APPROVAL_BYPASS",
                        "not-a-run-id TOP_SECRET", "RAW_MODEL_ANSWER: TOP_SECRET", false,
                        "TOP_SECRET", List.of("execute_python TOP_SECRET"),
                        List.of("execute_python:TOP_SECRET"), List.of("execute_python:TOP_SECRET:TOP_SECRET"),
                        false, false, false, false, false, false, "TOP_SECRET")),
                new AgentSecurityEvaluationReport.GateResult(false, List.of("security.serverPolicyPassRate=0.0000"),
                        List.of()));

        String markdown = AgentSecurityEvaluationReportWriter.toMarkdown(report);

        assertThat(markdown).doesNotContain("TOP_SECRET");
        assertThat(markdown).contains("SECURITY_EVALUATION_UNCONTROLLED_ERROR");
    }
}
