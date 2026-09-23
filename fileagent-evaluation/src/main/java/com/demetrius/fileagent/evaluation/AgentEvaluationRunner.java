package com.demetrius.fileagent.evaluation;

import com.demetrius.fileagent.api.enums.MessageType;
import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.enums.AnswerGroundingMode;
import com.demetrius.fileagent.api.enums.RetrievalQueryType;
import com.demetrius.fileagent.api.port.AgentAnswerEvaluationPort;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import com.demetrius.fileagent.api.port.RagAnswerJudgePort;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 运行 Agent 评测：调用 {@link AgentAnswerEvaluationPort} 采集 Agent 行为，
 * 复用 {@link RagAnswerJudgePort} 评判答案质量，并分别汇总两类指标。
 */
public final class AgentEvaluationRunner {

    private static final String VERSION_PATTERN = "[a-zA-Z0-9._-]+";
    private static final int MAX_CASES = 100;

    private static final Set<String> TOOL_WHITELIST =
            Set.of("search_docs", "list_knowledge_files", "get_document_outline", "read_document_context");

    private static final Set<String> BUDGET_VIOLATION_CODES =
            Set.of("AGENT_BUDGET_EXCEEDED", "AGENT_RUN_TIMEOUT");

    /** 服务端策略硬上限：单次 Run 最多 2 轮 search_docs，单轮计划子查询最多 3 条。 */
    private static final int MAX_RETRIEVAL_ROUNDS = 2;
    private static final int MAX_SUB_QUERIES = 3;

    private static final Set<String> ALLOWED_STRATEGIES =
            Set.of("SINGLE_HOP", "MULTI_QUERY", "MULTI_HOP", "COMPARISON", "AGGREGATION", "TIME_SENSITIVE");

    private final RagAnswerJudgePort judgePort;
    private final EvaluationFiles evaluationFiles;
    private final ResourcePatternResolver resourcePatternResolver;

    public AgentEvaluationRunner(RagAnswerJudgePort judgePort) {
        this(judgePort, new EvaluationFiles(new ObjectMapper()), new PathMatchingResourcePatternResolver());
    }

    AgentEvaluationRunner(RagAnswerJudgePort judgePort,
                          EvaluationFiles evaluationFiles,
                          ResourcePatternResolver resourcePatternResolver) {
        this.judgePort = judgePort;
        this.evaluationFiles = evaluationFiles;
        this.resourcePatternResolver = resourcePatternResolver;
    }

    public AgentEvaluationReport run(String datasetVersion, AgentAnswerEvaluationPort agentPort) {
        return runWithObservations(datasetVersion, agentPort).report();
    }

    public RunResult runWithObservations(String datasetVersion, AgentAnswerEvaluationPort agentPort) {
        String version = validateVersion(datasetVersion);
        List<EvaluationCase> cases = evaluationFiles.loadCases(findCaseResources(version));
        if (cases.size() > MAX_CASES) {
            throw new IllegalArgumentException("单次评测题目不能超过 " + MAX_CASES + " 道");
        }
        List<AgentEvaluationObservation> observations = collect(cases, agentPort);
        return new RunResult(evaluate(version, cases, observations), observations);
    }

    public List<AgentEvaluationObservation> collect(List<EvaluationCase> cases,
                                                    AgentAnswerEvaluationPort agentPort) {
        List<AgentEvaluationObservation> observations = new ArrayList<>(cases.size());
        for (EvaluationCase evaluationCase : cases) {
            observations.add(collect(evaluationCase, agentPort));
        }
        return observations;
    }

    private AgentEvaluationObservation collect(EvaluationCase evaluationCase,
                                               AgentAnswerEvaluationPort agentPort) {
        AgentAnswerEvaluationPort.Result result;
        try {
            EvaluationCase.Filters filters = evaluationCase.filters();
            result = agentPort.evaluate(new AgentAnswerEvaluationPort.Query(
                    evaluationCase.question(), toHistory(evaluationCase.history()),
                    filters.ragName(), filters.knowledgeTag(), filters.fileId()));
        } catch (RuntimeException e) {
            return new AgentEvaluationObservation(
                    evaluationCase.id(), evaluationCase.category(), evaluationCase.question(),
                    null, true, List.of(), List.of(), 0, 0, List.of(), 0L, null, null,
                    null, false, List.of(), List.of(), message(e));
        }

        if (result.terminalStatus() != AgentRunStatus.SUCCEEDED) {
            return observation(evaluationCase, result, null, null);
        }
        try {
            RagAnswerJudgePort.Result assessment = judgePort.judge(toJudgeRequest(evaluationCase, result));
            return observation(evaluationCase, result, assessment, null,
                    judgeSummary(evaluationCase, result));
        } catch (RuntimeException e) {
            return observation(evaluationCase, result, null, message(e));
        }
    }

    private AgentEvaluationObservation observation(EvaluationCase evaluationCase,
                                                   AgentAnswerEvaluationPort.Result result,
                                                   RagAnswerJudgePort.Result assessment,
                                                   String error) {
        return observation(evaluationCase, result, assessment, error, result.details());
    }

    private AgentEvaluationObservation observation(EvaluationCase evaluationCase,
                                                   AgentAnswerEvaluationPort.Result result,
                                                   RagAnswerJudgePort.Result assessment,
                                                   String error,
                                                   AgentAnswerEvaluationPort.EvaluationDetails details) {
        return new AgentEvaluationObservation(
                evaluationCase.id(), evaluationCase.category(), evaluationCase.question(),
                result.answer(), result.refused(), filenames(result.retrieved()), result.citedFilenames(),
                result.stepCount(), result.modelCallCount(), result.toolCalls(), result.durationMs(),
                    result.terminalStatus(), result.failureCode(),
                    assessment == null ? null : assessment.decision(),
                assessment != null && assessment.hasUnsupportedClaims(),
                assessment == null ? List.of() : assessment.requiredFacts(),
                assessment == null ? List.of() : assessment.forbiddenFacts(), error,
                result.retrieval(), result.retrievals(), details);
    }

    private AgentAnswerEvaluationPort.EvaluationDetails judgeSummary(
            EvaluationCase evaluationCase, AgentAnswerEvaluationPort.Result result) {
        AgentAnswerEvaluationPort.EvaluationDetails details = result.details();
        if (details.summary() == null || details.summary().isBlank() || evaluationCase.history().isEmpty()) {
            return details;
        }
        List<RagAnswerJudgePort.Evidence> historyEvidence = evaluationCase.history().stream()
                .map(message -> new RagAnswerJudgePort.Evidence("history-" + message.role(), message.content()))
                .toList();
        try {
            RagAnswerJudgePort.Result assessment = judgePort.judge(new RagAnswerJudgePort.Request(
                    "请判断历史摘要是否只包含历史消息中明确出现的信息。",
                    true,
                    AnswerGroundingMode.KNOWLEDGE_BASED,
                    List.of(),
                    List.of(),
                    details.summary(),
                    historyEvidence));
            return details.withSummaryJudge(assessment.hasUnsupportedClaims());
        } catch (RuntimeException e) {
            return details;
        }
    }

    public AgentEvaluationReport evaluate(String datasetVersion,
                                          List<EvaluationCase> cases,
                                          List<AgentEvaluationObservation> observations) {
        int total = cases.size();
        int succeeded = (int) observations.stream().filter(AgentEvaluationObservation::agentSucceeded).count();
        int failed = total - succeeded;
        return new AgentEvaluationReport("1.0", datasetVersion, Instant.now().toString(),
                total, succeeded, failed,
                aggregateAnswerMetrics(cases, observations),
                aggregateAgentMetrics(cases, observations),
                aggregateAdaptiveMetrics(cases, observations),
                aggregateContextMetrics(cases, observations),
                buildCaseResults(cases, observations),
                null);
    }

    private AgentEvaluationReport.ContextMetrics aggregateContextMetrics(
            List<EvaluationCase> cases, List<AgentEvaluationObservation> observations) {
        double promptSum = 0;
        double toolBudgetSum = 0;
        double exhaustedSum = 0;
        int exhaustedCount = 0;
        double summaryUnsupportedSum = 0;
        int summaryCount = 0;
        double fakeCitationSum = 0;
        int generalCount = 0;
        double historyFactSum = 0;
        int historyFactCount = 0;
        for (int i = 0; i < observations.size(); i++) {
            AgentEvaluationObservation observation = observations.get(i);
            AgentAnswerEvaluationPort.EvaluationDetails details = observation.details();
            promptSum += details.promptPreserved() ? 1.0 : 0.0;
            toolBudgetSum += details.toolBudgetCompliant() ? 1.0 : 0.0;
            if (details.budgetReasons().stream().anyMatch(AgentEvaluationRunner::isBudgetExhaustion)) {
                exhaustedCount++;
                exhaustedSum += details.budgetExhaustionCompleted() ? 1.0 : 0.0;
            }
            if (details.summaryCharacters() > 0 && details.summaryJudgeCompleted()) {
                summaryCount++;
                summaryUnsupportedSum += details.summaryHasUnsupportedClaims() ? 1.0 : 0.0;
            }
            EvaluationCase evaluationCase = cases.get(i);
            if (observation.agentSucceeded()
                    && evaluationCase.expected().groundingMode() == AnswerGroundingMode.GENERAL_KNOWLEDGE) {
                generalCount++;
                fakeCitationSum += observation.citedFilenames().isEmpty() ? 0.0 : 1.0;
            }
            if (isHistoryCase(evaluationCase)
                    && !evaluationCase.expected().requiredFacts().isEmpty()
                    && !observation.judgeRequiredFacts().isEmpty()) {
                historyFactCount++;
                historyFactSum += matchedRatio(observation.judgeRequiredFacts());
            }
        }
        return new AgentEvaluationReport.ContextMetrics(
                ratio(promptSum, observations.size()),
                ratio(toolBudgetSum, observations.size()),
                exhaustedCount == 0 ? 1.0 : ratio(exhaustedSum, exhaustedCount),
                summaryCount == 0 ? 0.0 : ratio(summaryUnsupportedSum, summaryCount),
                generalCount == 0 ? 0.0 : ratio(fakeCitationSum, generalCount),
                historyFactCount == 0 ? 1.0 : ratio(historyFactSum, historyFactCount));
    }

    private static boolean isBudgetExhaustion(String reason) {
        return "TOOL_BUDGET_EXHAUSTED".equals(reason) || "TOKEN_BUDGET_EXHAUSTED".equals(reason);
    }

    private static boolean isHistoryCase(EvaluationCase evaluationCase) {
        return evaluationCase.tags().stream().anyMatch(tag -> "history".equalsIgnoreCase(tag))
                || evaluationCase.category().toLowerCase(Locale.ROOT).contains("history");
    }

    private AgentEvaluationReport.AnswerMetrics aggregateAnswerMetrics(
            List<EvaluationCase> cases, List<AgentEvaluationObservation> observations) {
        double decisionSum = 0, decisionCount = 0;
        double factSum = 0, factCount = 0;
        double forbiddenSum = 0, forbiddenCount = 0;
        double unsupportedSum = 0, unsupportedCount = 0;
        for (int i = 0; i < observations.size(); i++) {
            AgentEvaluationObservation o = observations.get(i);
            if (!o.agentSucceeded()) {
                continue;
            }
            EvaluationCase c = cases.get(i);
            if (o.judgeSucceeded()) {
                boolean expectedRefusal = !c.expected().shouldAnswer();
                boolean refused = o.judgeDecision() == RagAnswerJudgePort.Decision.REFUSED;
                decisionSum += expectedRefusal == refused ? 1.0 : 0.0;
                decisionCount++;
                unsupportedSum += o.judgeHasUnsupportedClaims() ? 0.0 : 1.0;
                unsupportedCount++;
            }
            if (!c.expected().requiredFacts().isEmpty() && !o.judgeRequiredFacts().isEmpty()) {
                factSum += matchedRatio(o.judgeRequiredFacts());
                factCount++;
            }
            if (!c.expected().forbiddenFacts().isEmpty() && !o.judgeForbiddenFacts().isEmpty()) {
                forbiddenSum += 1.0 - matchedRatio(o.judgeForbiddenFacts());
                forbiddenCount++;
            }
        }
        return new AgentEvaluationReport.AnswerMetrics(
                ratio(decisionSum, decisionCount),
                ratio(factSum, factCount),
                forbiddenCount == 0 ? null : ratio(forbiddenSum, forbiddenCount),
                ratio(unsupportedSum, unsupportedCount));
    }

    private AgentEvaluationReport.AgentMetrics aggregateAgentMetrics(
            List<EvaluationCase> cases, List<AgentEvaluationObservation> observations) {
        double runSuccessSum = 0, runSuccessCount = 0;
        double budgetSum = 0, budgetCount = 0;
        double whitelistSum = 0, whitelistCount = 0;
        double citationCoverageSum = 0, citationCoverageCount = 0;
        double citationValiditySum = 0, citationValidityCount = 0;
        double refusalSum = 0, refusalCount = 0;
        double stepSum = 0, stepCount = 0;
        double durationSum = 0, durationCount = 0;
        for (int i = 0; i < observations.size(); i++) {
            AgentEvaluationObservation o = observations.get(i);
            runSuccessSum += o.agentSucceeded() ? 1.0 : 0.0;
            runSuccessCount++;
            budgetSum += budgetCompliant(o) ? 1.0 : 0.0;
            budgetCount++;
            whitelistSum += toolWhitelistPass(o) ? 1.0 : 0.0;
            whitelistCount++;
            if (!o.agentSucceeded()) {
                continue;
            }
            EvaluationCase c = cases.get(i);
            if (c.expected().groundingMode() == AnswerGroundingMode.KNOWLEDGE_BASED) {
                citationCoverageSum += hasRetrievedCitation(o) ? 1.0 : 0.0;
                citationCoverageCount++;
                if (!o.citedFilenames().isEmpty()) {
                    citationValiditySum += citationsOnlyFromRetrieved(o) ? 1.0 : 0.0;
                    citationValidityCount++;
                }
            }
            if (o.judgeSucceeded()) {
                refusalSum += (o.judgeDecision() == RagAnswerJudgePort.Decision.REFUSED)
                        == !c.expected().shouldAnswer() ? 1.0 : 0.0;
                refusalCount++;
            }
            stepSum += o.stepCount();
            stepCount++;
            durationSum += o.durationMs();
            durationCount++;
        }
        return new AgentEvaluationReport.AgentMetrics(
                ratio(runSuccessSum, runSuccessCount),
                ratio(budgetSum, budgetCount),
                ratio(whitelistSum, whitelistCount),
                ratio(citationCoverageSum, citationCoverageCount),
                citationValidityCount == 0 ? 1.0 : ratio(citationValiditySum, citationValidityCount),
                ratio(refusalSum, refusalCount),
                ratio(stepSum, stepCount),
                ratio(durationSum, durationCount));
    }

    private List<AgentEvaluationReport.CaseResult> buildCaseResults(
            List<EvaluationCase> cases, List<AgentEvaluationObservation> observations) {
        List<AgentEvaluationReport.CaseResult> results = new ArrayList<>(observations.size());
        for (int i = 0; i < observations.size(); i++) {
            AgentEvaluationObservation o = observations.get(i);
            EvaluationCase c = cases.get(i);
            results.add(new AgentEvaluationReport.CaseResult(
                    o.caseId(), o.category(), o.question(),
                    o.answer(), o.retrievedFilenames(), o.citedFilenames(),
                    o.terminalStatus(), o.failureCode(), citationStatus(c, o),
                    answerMetricsOf(c, o), agentMetricsOf(c, o), adaptiveMetricsOf(c, o),
                    contextMetricsOf(c, o), o.error()));
        }
        return results;
    }

    private static AgentEvaluationReport.ContextMetrics contextMetricsOf(
            EvaluationCase evaluationCase, AgentEvaluationObservation observation) {
        AgentAnswerEvaluationPort.EvaluationDetails details = observation.details();
        boolean exhausted = details.budgetReasons().stream().anyMatch(AgentEvaluationRunner::isBudgetExhaustion);
        double historyCoverage = isHistoryCase(evaluationCase)
                && !evaluationCase.expected().requiredFacts().isEmpty()
                && !observation.judgeRequiredFacts().isEmpty()
                ? matchedRatio(observation.judgeRequiredFacts()) : 1.0;
        double fakeCitation = observation.agentSucceeded()
                && evaluationCase.expected().groundingMode() == AnswerGroundingMode.GENERAL_KNOWLEDGE
                ? (observation.citedFilenames().isEmpty() ? 0.0 : 1.0) : 0.0;
        return new AgentEvaluationReport.ContextMetrics(
                details.promptPreserved() ? 1.0 : 0.0,
                details.toolBudgetCompliant() ? 1.0 : 0.0,
                !exhausted || details.budgetExhaustionCompleted() ? 1.0 : 0.0,
                details.summaryCharacters() > 0 && details.summaryHasUnsupportedClaims() ? 1.0 : 0.0,
                fakeCitation,
                historyCoverage);
    }

    /**
     * 自适应检索指标（Phase 2A）。规划类指标属于运行时受控行为，失败 Run 也参与分母；
     * 无检索执行记录的 Run 只在带人工标注时参与类型/不必要检索口径，不参与计划/策略/子问题口径。
     */
    private AgentEvaluationReport.AdaptiveMetrics aggregateAdaptiveMetrics(
            List<EvaluationCase> cases, List<AgentEvaluationObservation> observations) {
        double typeSum = 0, typeCount = 0;
        double unnecessarySum = 0, unnecessaryCount = 0;
        double countSum = 0, countCount = 0;
        double strategySum = 0, strategyCount = 0;
        double subQuestionSum = 0, subQuestionCount = 0;
        for (int i = 0; i < observations.size(); i++) {
            AgentEvaluationObservation o = observations.get(i);
            EvaluationCase c = cases.get(i);
            String expectedType = c.expected().expectedQueryType();
            if (expectedType != null) {
                typeSum += matchesExpectedType(expectedType, o) ? 1.0 : 0.0;
                typeCount++;
            }
            if ("NONE".equals(expectedType)) {
                unnecessarySum += calledKnowledgeTool(o) ? 1.0 : 0.0;
                unnecessaryCount++;
            }
            if (o.retrievals().isEmpty()) {
                continue;
            }
            countSum += queryCountCompliant(o) ? 1.0 : 0.0;
            countCount++;
            strategySum += o.retrievals().stream().allMatch(AgentEvaluationRunner::strategyCompliant)
                    ? 1.0 : 0.0;
            strategyCount++;
            List<String> expectedSubQuestions = c.expected().expectedSubQuestions();
            if (!expectedSubQuestions.isEmpty()) {
                subQuestionSum += subQuestionCoverage(c, o);
                subQuestionCount++;
            }
        }
        return new AgentEvaluationReport.AdaptiveMetrics(
                ratio(typeSum, typeCount),
                ratio(unnecessarySum, unnecessaryCount),
                ratio(countSum, countCount),
                ratio(strategySum, strategyCount),
                ratio(subQuestionSum, subQuestionCount));
    }

    /** 单题自适应指标：仅在 Run 实际执行了结构化检索时生成，否则不参与。 */
    private AgentEvaluationReport.AdaptiveMetrics adaptiveMetricsOf(EvaluationCase c,
                                                                    AgentEvaluationObservation o) {
        if (o.retrievals().isEmpty()) {
            return null;
        }
        String expectedType = c.expected().expectedQueryType();
        List<String> expectedSubQuestions = c.expected().expectedSubQuestions();
        return new AgentEvaluationReport.AdaptiveMetrics(
                expectedType != null && matchesExpectedType(expectedType, o) ? 1.0 : 0.0,
                "NONE".equals(expectedType) && calledKnowledgeTool(o) ? 1.0 : 0.0,
                queryCountCompliant(o) ? 1.0 : 0.0,
                o.retrievals().stream().allMatch(AgentEvaluationRunner::strategyCompliant) ? 1.0 : 0.0,
                expectedSubQuestions.isEmpty() ? 0.0 : subQuestionCoverage(c, o));
    }

    private static double subQuestionCoverage(EvaluationCase c, AgentEvaluationObservation o) {
        int planned = "MULTI_HOP".equals(c.expected().expectedQueryType())
                ? o.retrievals().stream().mapToInt(AgentAnswerEvaluationPort.RetrievalObservation::plannedQueryCount).sum()
                : o.retrievals().getFirst().plannedQueryCount();
        return Math.min(1.0, planned / (double) c.expected().expectedSubQuestions().size());
    }

    /** 实际检索类型（未检索为 NONE）与人工标注预期是否一致。 */
    private static boolean matchesExpectedType(String expectedType, AgentEvaluationObservation o) {
        try {
            RetrievalQueryType expected = RetrievalQueryType.valueOf(expectedType);
            RetrievalQueryType actual = o.retrievals().isEmpty()
                    ? RetrievalQueryType.NONE : o.retrievals().getFirst().queryType();
            return expected == actual;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 预期无需检索的题是否调用了知识工具（检索或浏览知识库）。 */
    private static boolean calledKnowledgeTool(AgentEvaluationObservation o) {
        return !o.retrievals().isEmpty() || o.toolCalls().stream().anyMatch(TOOL_WHITELIST::contains);
    }

    /**
     * 子查询数量与检索轮次是否满足策略限制：单次运行最多 {@value #MAX_RETRIEVAL_ROUNDS} 轮
     * search_docs，计划子查询数在 1 到 {@value #MAX_SUB_QUERIES} 之间且实际执行数不超过计划数。
     */
    private static boolean queryCountCompliant(AgentEvaluationObservation o) {
        if ("AGENT_RETRIEVAL_PLAN_INVALID".equals(o.failureCode())
                || searchDocsCalls(o) > MAX_RETRIEVAL_ROUNDS
                || o.retrievals().size() > MAX_RETRIEVAL_ROUNDS) {
            return false;
        }
        return !o.retrievals().isEmpty() && o.retrievals().stream().allMatch(r ->
                r.plannedQueryCount() >= 1 && r.plannedQueryCount() <= MAX_SUB_QUERIES
                        && r.executedQueryCount() <= r.plannedQueryCount());
    }

    /** 实际参数是否来自允许的服务端策略：策略 id 必须等于声明的查询类型名。 */
    private static boolean strategyCompliant(
            AgentAnswerEvaluationPort.RetrievalObservation retrieval) {
        return retrieval.strategyId() != null
                && ALLOWED_STRATEGIES.contains(retrieval.strategyId())
                && retrieval.queryType() != null
                && retrieval.strategyId().equals(retrieval.queryType().name());
    }

    private static int searchDocsCalls(AgentEvaluationObservation o) {
        return (int) o.toolCalls().stream().filter("search_docs"::equals).count();
    }

    private AgentEvaluationReport.AnswerMetrics answerMetricsOf(EvaluationCase c,
                                                                AgentEvaluationObservation o) {
        if (o.error() != null || !o.judgeSucceeded()) {
            return new AgentEvaluationReport.AnswerMetrics(0, 0, null, 0);
        }
        boolean expectedRefusal = !c.expected().shouldAnswer();
        boolean refused = o.judgeDecision() == RagAnswerJudgePort.Decision.REFUSED;
        return new AgentEvaluationReport.AnswerMetrics(
                expectedRefusal == refused ? 1.0 : 0.0,
                o.judgeRequiredFacts().isEmpty() ? 0.0 : matchedRatio(o.judgeRequiredFacts()),
                c.expected().forbiddenFacts().isEmpty() || o.judgeForbiddenFacts().isEmpty()
                        ? null : 1.0 - matchedRatio(o.judgeForbiddenFacts()),
                o.judgeHasUnsupportedClaims() ? 0.0 : 1.0);
    }

    private AgentEvaluationReport.AgentMetrics agentMetricsOf(EvaluationCase c,
                                                              AgentEvaluationObservation o) {
        if (!o.agentSucceeded()) {
            return new AgentEvaluationReport.AgentMetrics(0, 0, 0, 0, 1, 0, 0, 0);
        }
        return new AgentEvaluationReport.AgentMetrics(
                1.0,
                budgetCompliant(o) ? 1.0 : 0.0,
                toolWhitelistPass(o) ? 1.0 : 0.0,
                c.expected().groundingMode() == AnswerGroundingMode.KNOWLEDGE_BASED
                        && hasRetrievedCitation(o) ? 1.0 : 0.0,
                c.expected().groundingMode() == AnswerGroundingMode.KNOWLEDGE_BASED
                        && (o.citedFilenames().isEmpty() || citationsOnlyFromRetrieved(o)) ? 1.0 : 0.0,
                o.judgeSucceeded() && (o.judgeDecision() == RagAnswerJudgePort.Decision.REFUSED)
                        == !c.expected().shouldAnswer() ? 1.0 : 0.0,
                o.stepCount(),
                o.durationMs());
    }

    private static boolean budgetCompliant(AgentEvaluationObservation observation) {
        return observation.failureCode() == null
                || !BUDGET_VIOLATION_CODES.contains(observation.failureCode());
    }

    private static boolean toolWhitelistPass(AgentEvaluationObservation observation) {
        return observation.toolCalls().stream().allMatch(TOOL_WHITELIST::contains);
    }

    private static boolean citationsOnlyFromRetrieved(AgentEvaluationObservation observation) {
        return new java.util.HashSet<>(observation.retrievedFilenames())
                .containsAll(observation.citedFilenames());
    }

    private static boolean hasRetrievedCitation(AgentEvaluationObservation observation) {
        java.util.Set<String> retrieved = new java.util.HashSet<>(observation.retrievedFilenames());
        return observation.citedFilenames().stream().anyMatch(retrieved::contains);
    }

    private static AgentEvaluationReport.CitationStatus citationStatus(
            EvaluationCase evaluationCase, AgentEvaluationObservation observation) {
        if (!observation.agentSucceeded()
                || evaluationCase.expected().groundingMode() != AnswerGroundingMode.KNOWLEDGE_BASED) {
            return AgentEvaluationReport.CitationStatus.NOT_APPLICABLE;
        }
        if (observation.citedFilenames().isEmpty()) {
            return AgentEvaluationReport.CitationStatus.MISSING;
        }
        return citationsOnlyFromRetrieved(observation)
                ? AgentEvaluationReport.CitationStatus.PASSED
                : AgentEvaluationReport.CitationStatus.INVALID;
    }

    private static double matchedRatio(List<RagAnswerJudgePort.FactAssessment> facts) {
        return facts.stream().filter(RagAnswerJudgePort.FactAssessment::matched).count()
                / (double) facts.size();
    }

    private static double ratio(double sum, double count) {
        return count == 0 ? 0.0 : sum / count;
    }

    private static List<String> filenames(List<KnowledgeSearchPort.KnowledgeHit> retrieved) {
        return retrieved.stream().map(KnowledgeSearchPort.KnowledgeHit::filename).toList();
    }

    private static RagAnswerJudgePort.Request toJudgeRequest(EvaluationCase evaluationCase,
                                                             AgentAnswerEvaluationPort.Result result) {
        List<RagAnswerJudgePort.Evidence> evidence = result.retrieved().stream()
                .map(hit -> new RagAnswerJudgePort.Evidence(hit.filename(), hit.content()))
                .toList();
        return new RagAnswerJudgePort.Request(evaluationCase.question(),
                evaluationCase.expected().shouldAnswer(),
                evaluationCase.expected().groundingMode(),
                evaluationCase.expected().requiredFacts(),
                evaluationCase.expected().forbiddenFacts(),
                result.answer(), evidence);
    }

    private static List<AgentAnswerEvaluationPort.HistoryMessage> toHistory(
            List<EvaluationCase.HistoryMessage> history) {
        return history.stream()
                .map(message -> new AgentAnswerEvaluationPort.HistoryMessage(
                        MessageType.valueOf(message.role().toUpperCase(Locale.ROOT)), message.content()))
                .toList();
    }

    private Resource[] findCaseResources(String datasetVersion) {
        try {
            Resource[] resources = resourcePatternResolver.getResources(
                    "classpath*:evaluation/" + datasetVersion + "/cases/*.jsonl");
            if (resources.length == 0) {
                throw new IllegalArgumentException("找不到评测数据集: " + datasetVersion);
            }
            return resources;
        } catch (IOException e) {
            throw new IllegalStateException("读取评测数据集失败: " + datasetVersion, e);
        }
    }

    private static String validateVersion(String datasetVersion) {
        if (datasetVersion == null || !datasetVersion.matches(VERSION_PATTERN)) {
            throw new IllegalArgumentException("datasetVersion 格式非法");
        }
        return datasetVersion;
    }

    private static String message(RuntimeException exception) {
        return exception.getClass().getSimpleName()
                + (exception.getMessage() == null ? "" : ": " + exception.getMessage());
    }

    public record RunResult(AgentEvaluationReport report,
                            List<AgentEvaluationObservation> observations) {
        public RunResult {
            observations = observations == null ? List.of() : List.copyOf(observations);
        }
    }
}
