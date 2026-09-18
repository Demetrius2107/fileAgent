package com.demetrius.fileagent.evaluation;

import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.enums.AnswerGroundingMode;
import com.demetrius.fileagent.api.port.AgentAnswerEvaluationPort;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import com.demetrius.fileagent.api.port.RagAnswerJudgePort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEvaluationRunnerTest {

    @Test
    void generalKnowledgeShouldPassWithoutRetrievalOrCitation() {
        AtomicReference<RagAnswerJudgePort.Request> judgeRequest = new AtomicReference<>();
        RagAnswerJudgePort judgePort = request -> {
            judgeRequest.set(request);
            return new RagAnswerJudgePort.Result(RagAnswerJudgePort.Decision.ANSWERED,
                    "回答了通用知识", false, null,
                    List.of(new RagAnswerJudgePort.FactAssessment("HTTP 404 表示请求的资源未找到", true, "语义正确")),
                    List.of(), "{}", 1L);
        };
        AgentAnswerEvaluationPort agentPort = query -> new AgentAnswerEvaluationPort.Result(
                "HTTP 404 表示服务器找不到请求的资源。", false,
                List.of(), List.of(), 0, 1, List.of(), 50L,
                AgentRunStatus.SUCCEEDED, null);
        EvaluationCase evaluationCase = new EvaluationCase("1.0", "general-001", "GENERAL_KNOWLEDGE",
                List.of(), "HTTP 404 是什么意思？", List.of(), new EvaluationCase.Filters(null, null, null),
                new EvaluationCase.Expected(true, List.of(),
                        List.of("HTTP 404 表示请求的资源未找到"), List.of(),
                        AnswerGroundingMode.GENERAL_KNOWLEDGE));

        AgentEvaluationRunner runner = new AgentEvaluationRunner(judgePort);
        AgentEvaluationReport report = runner.evaluate("agent-v1", List.of(evaluationCase),
                runner.collect(List.of(evaluationCase), agentPort));

        assertThat(judgeRequest.get().groundingMode()).isEqualTo(AnswerGroundingMode.GENERAL_KNOWLEDGE);
        assertThat(judgeRequest.get().evidence()).isEmpty();
        assertThat(report.answerMetrics().answerDecisionAccuracy()).isEqualTo(1.0d);
        assertThat(report.answerMetrics().unsupportedClaimSafety()).isEqualTo(1.0d);
        assertThat(report.agentMetrics().citationCoverageRate()).isEqualTo(0.0d);
        assertThat(report.agentMetrics().citationValidityRate()).isEqualTo(1.0d);
        assertThat(report.cases()).singleElement()
                .extracting(AgentEvaluationReport.CaseResult::citationStatus)
                .isEqualTo(AgentEvaluationReport.CitationStatus.NOT_APPLICABLE);
    }

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
        assertThat(report.agentMetrics().citationCoverageRate()).isEqualTo(1.0d);
        assertThat(report.agentMetrics().citationValidityRate()).isEqualTo(1.0d);
    }

    @Test
    void shouldFlagBudgetViolationAndOffWhitelistTool() {
        AtomicInteger judgeCalls = new AtomicInteger();
        RagAnswerJudgePort judgePort = request -> {
            judgeCalls.incrementAndGet();
            return new RagAnswerJudgePort.Result(
                    RagAnswerJudgePort.Decision.ANSWERED, "回答了", false, "无",
                    List.of(), List.of(), "{}", 1L);
        };
        AgentAnswerEvaluationPort agentPort = query -> new AgentAnswerEvaluationPort.Result(
                "答案", false, List.of(), List.of(), 5, 5,
                List.of("search_docs", "delete_all_docs"), 200L,
                AgentRunStatus.FAILED, "AGENT_BUDGET_EXCEEDED");
        AgentEvaluationRunner runner = new AgentEvaluationRunner(judgePort);

        AgentEvaluationReport report = runner.run("agent-v1", agentPort);

        assertThat(report.agentMetrics().budgetComplianceRate()).isEqualTo(0.0d);
        assertThat(report.agentMetrics().toolWhitelistPassRate()).isEqualTo(0.0d);
        assertThat(report.successfulCases()).isZero();
        assertThat(report.agentMetrics().runSuccessRate()).isEqualTo(0.0d);
        assertThat(judgeCalls).hasValue(0);
    }

    @Test
    void citationMetricsShouldSeparateCoverageFromValidity() {
        EvaluationCase missingCase = knowledgeCase("knowledge-001", "员工年假是多少？", "5 天");
        EvaluationCase mixedCase = knowledgeCase("knowledge-002", "员工年假如何申请？", "申请");
        AgentEvaluationObservation missing = observation(
                "knowledge-001", List.of("employee-handbook.md"), List.of());
        AgentEvaluationObservation mixed = observation(
                "knowledge-002", List.of("employee-handbook.md"),
                List.of("employee-handbook.md", "forged.md"));

        AgentEvaluationReport report = new AgentEvaluationRunner(request -> null)
                .evaluate("agent-v1", List.of(missingCase, mixedCase), List.of(missing, mixed));

        assertThat(report.agentMetrics().citationCoverageRate()).isEqualTo(0.5d);
        assertThat(report.agentMetrics().citationValidityRate()).isEqualTo(0.0d);
        assertThat(report.cases()).extracting(AgentEvaluationReport.CaseResult::citationStatus)
                .containsExactly(AgentEvaluationReport.CitationStatus.MISSING,
                        AgentEvaluationReport.CitationStatus.INVALID);
    }

    @Test
    void gateShouldEnforceMinimumScores() {
        AgentEvaluationReport report = new AgentEvaluationReport("1.0", "agent-v1", "now", 8, 8, 0,
                new AgentEvaluationReport.AnswerMetrics(1.0, 1.0, 1.0, 1.0),
                new AgentEvaluationReport.AgentMetrics(1.0, 0.5, 1.0, 1.0, 1.0, 1.0, 2.0, 100.0),
                List.of(), null);
        QualityGateConfig config = new QualityGateConfig("1.0",
                java.util.Map.of("agent.budgetComplianceRate", 1.0), 0.1, List.of());

        AgentEvaluationReport.GateResult gate =
                new AgentQualityGateEvaluator().evaluate(report, config, null);

        assertThat(gate.passed()).isFalse();
        assertThat(gate.violations()).anyMatch(v -> v.contains("budgetComplianceRate"));
    }

    private EvaluationCase knowledgeCase(String id, String question, String fact) {
        return new EvaluationCase("1.0", id, "KNOWLEDGE_BASED", List.of(), question,
                List.of(), new EvaluationCase.Filters(null, null, null),
                new EvaluationCase.Expected(true, List.of(), List.of(fact), List.of(),
                        AnswerGroundingMode.KNOWLEDGE_BASED));
    }

    private AgentEvaluationObservation observation(String caseId,
                                                   List<String> retrieved,
                                                   List<String> cited) {
        return new AgentEvaluationObservation(
                caseId, "KNOWLEDGE_BASED", "问题", "回答", false,
                retrieved, cited, 1, 1, List.of("search_docs"), 10L,
                AgentRunStatus.SUCCEEDED, null, null, false, List.of(), List.of(), null);
    }
}
