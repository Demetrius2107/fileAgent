package com.demetrius.fileagent.evaluation;

import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.enums.AnswerGroundingMode;
import com.demetrius.fileagent.api.enums.RetrievalQueryType;
import com.demetrius.fileagent.api.port.AgentAnswerEvaluationPort;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import com.demetrius.fileagent.api.port.RagAnswerJudgePort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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
                AgentRunStatus.SUCCEEDED, null, null);
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
                AgentRunStatus.SUCCEEDED, null, null);
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
                AgentRunStatus.FAILED, "AGENT_BUDGET_EXCEEDED", null);
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
    void forbiddenFactSafetyShouldBeNotApplicableWithoutAnnotatedSamples() {
        EvaluationCase evaluationCase = knowledgeCase("knowledge-001", "员工年假是多少？", "5 天");
        AgentEvaluationObservation observation = new AgentEvaluationObservation(
                "knowledge-001", "KNOWLEDGE_BASED", "员工年假是多少？", "年假 5 天", false,
                List.of("employee-handbook.md"), List.of(), 1, 1, List.of("search_docs"), 10L,
                AgentRunStatus.SUCCEEDED, null, RagAnswerJudgePort.Decision.ANSWERED, false,
                List.of(new RagAnswerJudgePort.FactAssessment("5 天", true, "覆盖")), List.of(), null);

        AgentEvaluationReport report = new AgentEvaluationRunner(request -> null)
                .evaluate("agent-v1", List.of(evaluationCase), List.of(observation));

        assertThat(report.answerMetrics().forbiddenFactSafety()).isNull();
        assertThat(report.cases().getFirst().answer().forbiddenFactSafety()).isNull();
        assertThat(AgentEvaluationReportWriter.toMarkdown(report))
                .contains("| 禁答内容安全性 | 不适用（无禁答事实样本） |");
        QualityGateConfig config = new QualityGateConfig("1.0",
                java.util.Map.of("answer.forbiddenFactSafety", 0.9), 0.1, List.of());
        assertThat(new AgentQualityGateEvaluator().evaluate(report, config, null).violations())
                .anyMatch(violation -> violation.contains("缺少结果"));
    }

    @Test
    void refusalAccuracyShouldUseJudgeDecisionInsteadOfAnswerEmptiness() {
        EvaluationCase noAnswerCase = new EvaluationCase("1.0", "no-answer", "NO_ANSWER",
                List.of(), "公司健身房几点关门？", List.of(),
                new EvaluationCase.Filters(null, null, null),
                new EvaluationCase.Expected(false, List.of(), List.of(), List.of(),
                        AnswerGroundingMode.REFUSE));
        AgentEvaluationObservation noAnswer = new AgentEvaluationObservation(
                "no-answer", "NO_ANSWER", "公司健身房几点关门？", "没有资料，无法回答。", false,
                List.of(), List.of(), 1, 1, List.of("search_docs"), 10L,
                AgentRunStatus.SUCCEEDED, null, RagAnswerJudgePort.Decision.REFUSED, false,
                List.of(), List.of(), null);
        EvaluationCase answerCase = knowledgeCase("answer", "员工年假是多少？", "5 天");
        AgentEvaluationObservation answered = new AgentEvaluationObservation(
                "answer", "KNOWLEDGE_BASED", "员工年假是多少？", "年假 5 天", true,
                List.of(), List.of(), 1, 1, List.of("search_docs"), 10L,
                AgentRunStatus.SUCCEEDED, null, RagAnswerJudgePort.Decision.ANSWERED, false,
                List.of(), List.of(), null);

        AgentEvaluationReport report = new AgentEvaluationRunner(request -> null)
                .evaluate("agent-v1", List.of(noAnswerCase, answerCase), List.of(noAnswer, answered));

        assertThat(report.agentMetrics().refusalDecisionAccuracy()).isEqualTo(1.0);
        assertThat(report.cases()).extracting(c -> c.agent().refusalDecisionAccuracy())
                .containsExactly(1.0, 1.0);
    }

    @Test
    void forbiddenFactSafetyShouldScoreAnnotatedFacts() {
        EvaluationCase evaluationCase = new EvaluationCase("1.0", "forbidden", "KNOWLEDGE_BASED",
                List.of(), "员工年假是多少？", List.of(), new EvaluationCase.Filters(null, null, null),
                new EvaluationCase.Expected(true, List.of(), List.of(), List.of("3 天", "7 天"),
                        AnswerGroundingMode.KNOWLEDGE_BASED));
        AgentEvaluationObservation observation = new AgentEvaluationObservation(
                "forbidden", "KNOWLEDGE_BASED", "员工年假是多少？", "年假 7 天", false,
                List.of(), List.of(), 1, 1, List.of(), 10L,
                AgentRunStatus.SUCCEEDED, null, RagAnswerJudgePort.Decision.ANSWERED, false,
                List.of(), List.of(
                        new RagAnswerJudgePort.FactAssessment("3 天", false, "未出现"),
                        new RagAnswerJudgePort.FactAssessment("7 天", true, "出现")), null);

        AgentEvaluationReport report = new AgentEvaluationRunner(request -> null)
                .evaluate("agent-v1", List.of(evaluationCase), List.of(observation));

        assertThat(report.answerMetrics().forbiddenFactSafety()).isEqualTo(0.5);
        assertThat(report.cases().getFirst().answer().forbiddenFactSafety()).isEqualTo(0.5);
    }

    @Test
    void refusalAccuracyShouldExcludeJudgeFailures() {
        EvaluationCase evaluationCase = knowledgeCase("knowledge-001", "员工年假是多少？", "5 天");
        AgentEvaluationObservation judged = new AgentEvaluationObservation(
                "knowledge-001", "KNOWLEDGE_BASED", "员工年假是多少？", "年假 5 天", false,
                List.of(), List.of(), 1, 1, List.of(), 10L,
                AgentRunStatus.SUCCEEDED, null, RagAnswerJudgePort.Decision.ANSWERED, false,
                List.of(), List.of(), null);
        AgentEvaluationObservation judgeFailed = new AgentEvaluationObservation(
                "knowledge-002", "KNOWLEDGE_BASED", "员工年假是多少？", "年假 5 天", false,
                List.of(), List.of(), 1, 1, List.of(), 10L,
                AgentRunStatus.SUCCEEDED, null, null, false,
                List.of(), List.of(), "Judge 输出不合法");

        AgentEvaluationReport report = new AgentEvaluationRunner(request -> null)
                .evaluate("agent-v1", List.of(evaluationCase, knowledgeCase(
                        "knowledge-002", "员工年假是多少？", "5 天")), List.of(judged, judgeFailed));

        assertThat(report.agentMetrics().refusalDecisionAccuracy()).isEqualTo(1.0);
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

    @Test
    void adaptiveMetricsShouldMeasurePlanningComplianceAndAccuracy() {
        List<AgentEvaluationObservation> observations = List.of(
                adaptiveObservation("single-001",
                        retrievalObservation(RetrievalQueryType.SINGLE_HOP, 1, 1)),
                adaptiveObservation("multi-001",
                        retrievalObservation(RetrievalQueryType.MULTI_HOP, 2, 2)),
                adaptiveObservation("compare-001",
                        retrievalObservation(RetrievalQueryType.COMPARISON, 2, 2)),
                adaptiveObservation("type-mismatch-001",
                        retrievalObservation(RetrievalQueryType.SINGLE_HOP, 1, 1)),
                new AgentEvaluationObservation(
                        "plan-invalid-001", "ADAPTIVE", "问题", null, true, List.of(), List.of(),
                        3, 3, List.of("search_docs", "search_docs", "search_docs"), 100L,
                        AgentRunStatus.FAILED, "AGENT_RETRIEVAL_PLAN_INVALID", null, false,
                        List.of(), List.of(), null,
                        retrievalObservation(RetrievalQueryType.MULTI_HOP, 2, 2)));
        List<EvaluationCase> cases = List.of(
                adaptiveCase("single-001", "SINGLE_HOP", List.of()),
                adaptiveCase("multi-001", "MULTI_HOP", List.of("子问题 A", "子问题 B")),
                adaptiveCase("compare-001", "COMPARISON", List.of("对比 A", "对比 B")),
                adaptiveCase("type-mismatch-001", "AGGREGATION", List.of()),
                adaptiveCase("plan-invalid-001", "MULTI_HOP", List.of("子问题 A", "子问题 B")));

        AgentEvaluationReport report = new AgentEvaluationRunner(request -> null)
                .evaluate("adaptive-v1", cases, observations);

        AgentEvaluationReport.AdaptiveMetrics adaptive = report.adaptiveMetrics();
        assertThat(adaptive.queryTypeAccuracy()).isEqualTo(0.8d);
        assertThat(adaptive.unnecessaryRetrievalRate()).isEqualTo(0.0d);
        assertThat(adaptive.queryCountComplianceRate()).isEqualTo(0.8d);
        assertThat(adaptive.strategyComplianceRate()).isEqualTo(1.0d);
        assertThat(adaptive.subQuestionCoverage()).isEqualTo(1.0d);
    }

    @Test
    void adaptiveMetricsShouldUseFirstPlanForTypeAndEveryRoundForCompliance() {
        AgentAnswerEvaluationPort.RetrievalObservation first =
                retrievalObservation(RetrievalQueryType.MULTI_QUERY, 2, 2);
        AgentAnswerEvaluationPort.RetrievalObservation retry =
                retrievalObservation(RetrievalQueryType.SINGLE_HOP, 1, 1);
        AgentEvaluationObservation observation = new AgentEvaluationObservation(
                "multi-001", "ADAPTIVE", "问题", "回答", false, List.of("a.md"), List.of("a.md"),
                2, 2, List.of("search_docs", "search_docs"), 10L, AgentRunStatus.SUCCEEDED,
                null, RagAnswerJudgePort.Decision.ANSWERED, false, List.of(), List.of(), null,
                retry, List.of(first, retry));
        AgentEvaluationReport report = new AgentEvaluationRunner(request -> null).evaluate("adaptive-v2",
                List.of(adaptiveCase("multi-001", "MULTI_QUERY", List.of("事实一", "事实二"))),
                List.of(observation));

        assertThat(report.adaptiveMetrics().queryTypeAccuracy()).isEqualTo(1.0);
        assertThat(report.adaptiveMetrics().subQuestionCoverage()).isEqualTo(1.0);
        assertThat(report.cases().getFirst().adaptive().queryTypeAccuracy()).isEqualTo(1.0);
    }

    @Test
    void adaptiveMetricsShouldRejectInvalidSecondRoundStrategy() {
        AgentAnswerEvaluationPort.RetrievalObservation first =
                retrievalObservation(RetrievalQueryType.MULTI_QUERY, 2, 2);
        AgentAnswerEvaluationPort.RetrievalObservation invalid = new AgentAnswerEvaluationPort.RetrievalObservation(
                RetrievalQueryType.SINGLE_HOP, "COMPARISON", 4, 4,
                List.of(1, 1, 1, 1), List.of(), List.of(), true, true, null);
        AgentEvaluationObservation observation = new AgentEvaluationObservation(
                "multi-001", "ADAPTIVE", "问题", "回答", false, List.of(), List.of(),
                2, 2, List.of("search_docs", "search_docs"), 10L, AgentRunStatus.SUCCEEDED,
                null, RagAnswerJudgePort.Decision.ANSWERED, false, List.of(), List.of(), null,
                invalid, List.of(first, invalid));
        AgentEvaluationReport report = new AgentEvaluationRunner(request -> null).evaluate("adaptive-v2",
                List.of(adaptiveCase("multi-001", "MULTI_QUERY", List.of())), List.of(observation));

        assertThat(report.adaptiveMetrics().queryTypeAccuracy()).isEqualTo(1.0);
        assertThat(report.adaptiveMetrics().queryCountComplianceRate()).isZero();
        assertThat(report.adaptiveMetrics().strategyComplianceRate()).isZero();
    }

    @Test
    void multiHopCoverageShouldCountSequentialQueriesAcrossRounds() {
        AgentAnswerEvaluationPort.RetrievalObservation first =
                retrievalObservation(RetrievalQueryType.MULTI_HOP, 1, 1);
        AgentAnswerEvaluationPort.RetrievalObservation second =
                retrievalObservation(RetrievalQueryType.MULTI_HOP, 1, 1);
        AgentEvaluationObservation observation = new AgentEvaluationObservation(
                "hop-001", "ADAPTIVE", "问题", "回答", false, List.of(), List.of(),
                2, 2, List.of("search_docs", "search_docs"), 10L, AgentRunStatus.SUCCEEDED,
                null, RagAnswerJudgePort.Decision.ANSWERED, false, List.of(), List.of(), null,
                second, List.of(first, second));
        AgentEvaluationReport report = new AgentEvaluationRunner(request -> null).evaluate("adaptive-v2",
                List.of(adaptiveCase("hop-001", "MULTI_HOP", List.of("中间结果", "最终事实"))),
                List.of(observation));

        assertThat(report.adaptiveMetrics().queryCountComplianceRate()).isEqualTo(1.0);
        assertThat(report.adaptiveMetrics().subQuestionCoverage()).isEqualTo(1.0);
    }

    @Test
    void unnecessaryRetrievalShouldCountOnlyAnnotatedNoneCases() {
        List<AgentEvaluationObservation> observations = List.of(
                new AgentEvaluationObservation(
                        "none-001", "ADAPTIVE", "问题", "回答", false, List.of(), List.of(),
                        0, 1, List.of(), 10L, AgentRunStatus.SUCCEEDED, null, null, false,
                        List.of(), List.of(), null, null),
                new AgentEvaluationObservation(
                        "none-002", "ADAPTIVE", "问题", "回答", false, List.of("a.md"), List.of(),
                        1, 2, List.of("search_docs"), 20L, AgentRunStatus.SUCCEEDED, null, null,
                        false, List.of(), List.of(), null,
                        retrievalObservation(RetrievalQueryType.SINGLE_HOP, 1, 1)),
                new AgentEvaluationObservation(
                        "none-003", "ADAPTIVE", "问题", "回答", false, List.of(), List.of(),
                        1, 1, List.of("list_knowledge_files"), 10L, AgentRunStatus.SUCCEEDED,
                        null, null, false, List.of(), List.of(), null, null),
                adaptiveObservation("single-001",
                        retrievalObservation(RetrievalQueryType.SINGLE_HOP, 1, 1)));
        List<EvaluationCase> cases = List.of(
                adaptiveCase("none-001", "NONE", List.of()),
                adaptiveCase("none-002", "NONE", List.of()),
                adaptiveCase("none-003", "NONE", List.of()),
                adaptiveCase("single-001", "SINGLE_HOP", List.of()));

        AgentEvaluationReport report = new AgentEvaluationRunner(request -> null)
                .evaluate("adaptive-v1", cases, observations);

        assertThat(report.adaptiveMetrics().unnecessaryRetrievalRate())
                .isCloseTo(2.0d / 3.0d, within(1e-6));
        assertThat(report.adaptiveMetrics().queryTypeAccuracy()).isEqualTo(0.75d);
    }

    @Test
    void nonAdaptiveDatasetShouldKeepZeroMetricsWithoutNaN() {
        List<EvaluationCase> cases = List.of(
                knowledgeCase("knowledge-001", "员工年假是多少？", "5 天"),
                knowledgeCase("knowledge-002", "员工年假如何申请？", "申请"));
        List<AgentEvaluationObservation> observations = List.of(
                observation("knowledge-001", List.of("employee-handbook.md"), List.of()),
                observation("knowledge-002", List.of("employee-handbook.md"),
                        List.of("employee-handbook.md")));

        AgentEvaluationReport report = new AgentEvaluationRunner(request -> null)
                .evaluate("agent-v1", cases, observations);

        AgentEvaluationReport.AdaptiveMetrics adaptive = report.adaptiveMetrics();
        assertThat(adaptive.queryTypeAccuracy()).isEqualTo(0.0d);
        assertThat(adaptive.unnecessaryRetrievalRate()).isEqualTo(0.0d);
        assertThat(adaptive.queryCountComplianceRate()).isEqualTo(0.0d);
        assertThat(adaptive.strategyComplianceRate()).isEqualTo(0.0d);
        assertThat(adaptive.subQuestionCoverage()).isEqualTo(0.0d);
        assertThat(report.cases()).allSatisfy(c -> assertThat(c.adaptive()).isNull());
    }

    @Test
    void gateShouldFlattenAdaptiveMetrics() {
        AgentEvaluationReport report = new AgentEvaluationReport("1.0", "adaptive-v1", "now", 2, 2, 0,
                new AgentEvaluationReport.AnswerMetrics(1.0, 1.0, 1.0, 1.0),
                new AgentEvaluationReport.AgentMetrics(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0, 100.0),
                new AgentEvaluationReport.AdaptiveMetrics(0.5, 0.0, 1.0, 1.0, 1.0),
                List.of(), null);
        QualityGateConfig config = new QualityGateConfig("1.0",
                java.util.Map.of("adaptive.queryCountComplianceRate", 1.0,
                        "adaptive.queryTypeAccuracy", 1.0), 0.1, List.of());

        AgentEvaluationReport.GateResult gate =
                new AgentQualityGateEvaluator().evaluate(report, config, null);

        assertThat(gate.passed()).isFalse();
        assertThat(gate.violations()).anyMatch(v -> v.contains("queryTypeAccuracy"));
        assertThat(gate.violations()).noneMatch(v -> v.contains("queryCountComplianceRate"));
    }

    private EvaluationCase adaptiveCase(String id, String expectedQueryType,
                                        List<String> expectedSubQuestions) {
        return new EvaluationCase("1.0", id, "ADAPTIVE", List.of(), "问题",
                List.of(), new EvaluationCase.Filters(null, null, null),
                new EvaluationCase.Expected(true, List.of(), List.of(), List.of(),
                        null, expectedQueryType, expectedSubQuestions));
    }

    private AgentAnswerEvaluationPort.RetrievalObservation retrievalObservation(
            RetrievalQueryType queryType, int planned, int executed) {
        return new AgentAnswerEvaluationPort.RetrievalObservation(
                queryType, queryType.name(), planned, executed,
                List.of(), List.of(), List.of(), false, false, null);
    }

    private AgentEvaluationObservation adaptiveObservation(
            String caseId, AgentAnswerEvaluationPort.RetrievalObservation retrieval) {
        return new AgentEvaluationObservation(
                caseId, "ADAPTIVE", "问题", "回答", false, List.of("a.md"), List.of("a.md"),
                1, 2, List.of("search_docs"), 10L, AgentRunStatus.SUCCEEDED, null, null,
                false, List.of(), List.of(), null, retrieval);
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
