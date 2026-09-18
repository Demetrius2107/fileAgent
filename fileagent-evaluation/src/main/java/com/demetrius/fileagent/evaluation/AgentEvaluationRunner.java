package com.demetrius.fileagent.evaluation;

import com.demetrius.fileagent.api.enums.MessageType;
import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.enums.AnswerGroundingMode;
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
            Set.of("search_docs", "list_knowledge_files", "read_document_context");

    private static final Set<String> BUDGET_VIOLATION_CODES =
            Set.of("AGENT_BUDGET_EXCEEDED", "AGENT_RUN_TIMEOUT");

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
            return observation(evaluationCase, result, assessment, null);
        } catch (RuntimeException e) {
            return observation(evaluationCase, result, null, message(e));
        }
    }

    private AgentEvaluationObservation observation(EvaluationCase evaluationCase,
                                                   AgentAnswerEvaluationPort.Result result,
                                                   RagAnswerJudgePort.Result assessment,
                                                   String error) {
        return new AgentEvaluationObservation(
                evaluationCase.id(), evaluationCase.category(), evaluationCase.question(),
                result.answer(), result.refused(), filenames(result.retrieved()), result.citedFilenames(),
                result.stepCount(), result.modelCallCount(), result.toolCalls(), result.durationMs(),
                result.terminalStatus(), result.failureCode(),
                assessment == null ? null : assessment.decision(),
                assessment != null && assessment.hasUnsupportedClaims(),
                assessment == null ? List.of() : assessment.requiredFacts(),
                assessment == null ? List.of() : assessment.forbiddenFacts(), error);
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
                buildCaseResults(cases, observations),
                null);
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
                ratio(forbiddenSum, forbiddenCount),
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
            refusalSum += o.refused() == !c.expected().shouldAnswer() ? 1.0 : 0.0;
            refusalCount++;
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
                    answerMetricsOf(c, o), agentMetricsOf(c, o), o.error()));
        }
        return results;
    }

    private AgentEvaluationReport.AnswerMetrics answerMetricsOf(EvaluationCase c,
                                                                AgentEvaluationObservation o) {
        if (o.error() != null || !o.judgeSucceeded()) {
            return new AgentEvaluationReport.AnswerMetrics(0, 0, 0, 0);
        }
        boolean expectedRefusal = !c.expected().shouldAnswer();
        boolean refused = o.judgeDecision() == RagAnswerJudgePort.Decision.REFUSED;
        return new AgentEvaluationReport.AnswerMetrics(
                expectedRefusal == refused ? 1.0 : 0.0,
                o.judgeRequiredFacts().isEmpty() ? 0.0 : matchedRatio(o.judgeRequiredFacts()),
                o.judgeForbiddenFacts().isEmpty() ? 0.0 : 1.0 - matchedRatio(o.judgeForbiddenFacts()),
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
                o.refused() == !c.expected().shouldAnswer() ? 1.0 : 0.0,
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
