package com.demetrius.fileagent.evaluation;

import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.port.AgentAnswerEvaluationPort;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import com.demetrius.fileagent.api.port.RagAnswerJudgePort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEvaluationRunnerTest {

    @Test
    void reportShouldSeparateAnswerQualityFromAgentBehavior() {
        RagAnswerJudgePort judgePort = request -> new RagAnswerJudgePort.Result(
                request.shouldAnswer() ? RagAnswerJudgePort.Decision.ANSWERED : RagAnswerJudgePort.Decision.REFUSED,
                "决策正确", false, "无无依据内容",
                List.of(new RagAnswerJudgePort.FactAssessment("要点", true, "覆盖")), List.of(),
                "{}", 5L);
        AgentAnswerEvaluationPort agentPort = query -> new AgentAnswerEvaluationPort.Result(
                "年假 5 天 [来源：employee-handbook.md]", false,
                List.of(new KnowledgeSearchPort.KnowledgeHit(
                        "chunk-1", 1L, "员工入职第一年有 5 天年假", "employee-handbook.md",
                        null, "section-1", "parent-1", 2, 0.9)),
                List.of("employee-handbook.md"), 1, 2, List.of("search_docs"), 150L,
                AgentRunStatus.SUCCEEDED, null);
        AgentEvaluationRunner runner = new AgentEvaluationRunner(judgePort);

        AgentEvaluationReport report = runner.run("agent-v1", agentPort);

        assertThat(report.totalCases()).isEqualTo(8);
        assertThat(report.successfulCases()).isEqualTo(8);
        assertThat(report.answerMetrics().answerDecisionAccuracy()).isEqualTo(1.0d);
        assertThat(report.agentMetrics().budgetComplianceRate()).isEqualTo(1.0d);
        assertThat(report.agentMetrics().toolWhitelistPassRate()).isEqualTo(1.0d);
        assertThat(report.agentMetrics().citationOnlyFromRetrievedRate()).isEqualTo(1.0d);
    }

    @Test
    void shouldFlagBudgetViolationAndOffWhitelistTool() {
        RagAnswerJudgePort judgePort = request -> new RagAnswerJudgePort.Result(
                RagAnswerJudgePort.Decision.ANSWERED, "回答了", false, "无",
                List.of(), List.of(), "{}", 1L);
        AgentAnswerEvaluationPort agentPort = query -> new AgentAnswerEvaluationPort.Result(
                "答案", false, List.of(), List.of(), 5, 5,
                List.of("search_docs", "delete_all_docs"), 200L,
                AgentRunStatus.FAILED, "AGENT_BUDGET_EXCEEDED");
        AgentEvaluationRunner runner = new AgentEvaluationRunner(judgePort);

        AgentEvaluationReport report = runner.run("agent-v1", agentPort);

        assertThat(report.agentMetrics().budgetComplianceRate()).isEqualTo(0.0d);
        assertThat(report.agentMetrics().toolWhitelistPassRate()).isEqualTo(0.0d);
    }

    @Test
    void gateShouldEnforceMinimumScores() {
        AgentEvaluationReport report = new AgentEvaluationReport("1.0", "agent-v1", "now", 8, 8, 0,
                new AgentEvaluationReport.AnswerMetrics(1.0, 1.0, 1.0, 1.0),
                new AgentEvaluationReport.AgentMetrics(0.5, 1.0, 1.0, 1.0, 2.0, 100.0),
                List.of(), null);
        QualityGateConfig config = new QualityGateConfig("1.0",
                java.util.Map.of("agent.budgetComplianceRate", 1.0), 0.1, List.of());

        AgentEvaluationReport.GateResult gate =
                new AgentQualityGateEvaluator().evaluate(report, config, null);

        assertThat(gate.passed()).isFalse();
        assertThat(gate.violations()).anyMatch(v -> v.contains("budgetComplianceRate"));
    }
}
