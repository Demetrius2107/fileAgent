package com.demetrius.fileagent.evaluation;

import com.demetrius.fileagent.api.enums.MessageType;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import com.demetrius.fileagent.api.port.RagAnswerEvaluationPort;
import com.demetrius.fileagent.api.port.RagAnswerJudgePort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 调用正式检索端口并采集回答、检索与 Judge 评判结果。
 *
 * @author raosaijie
 */
public final class EvaluationRunner {

    private final RagAnswerEvaluationPort ragAnswerEvaluationPort;
    private final RagAnswerJudgePort ragAnswerJudgePort;

    public EvaluationRunner(RagAnswerEvaluationPort ragAnswerEvaluationPort,
                            RagAnswerJudgePort ragAnswerJudgePort) {
        this.ragAnswerEvaluationPort = ragAnswerEvaluationPort;
        this.ragAnswerJudgePort = ragAnswerJudgePort;
    }

    public List<EvaluationObservation> collect(List<EvaluationCase> cases) {
        List<EvaluationObservation> observations = new ArrayList<>(cases.size());
        for (EvaluationCase evaluationCase : cases) {
            observations.add(collect(evaluationCase));
        }
        return observations;
    }

    private EvaluationObservation collect(EvaluationCase evaluationCase) {
        long startedAt = System.nanoTime();
        try {
            EvaluationCase.Filters filters = evaluationCase.filters();
            RagAnswerEvaluationPort.Query query = new RagAnswerEvaluationPort.Query(
                    evaluationCase.question(), toHistory(evaluationCase.history()),
                    filters.ragName(), filters.knowledgeTag(), filters.fileId());
            RagAnswerEvaluationPort.Result result = ragAnswerEvaluationPort.evaluate(query);
            RagAnswerJudgePort.Result assessment = ragAnswerJudgePort.judge(toJudgeRequest(evaluationCase, result));
            List<EvaluationObservation.ObservedSource> sources = result.retrieved().stream()
                    .map(EvaluationRunner::toObservedSource)
                    .toList();
            List<EvaluationObservation.ObservedSource> citations = result.citations().stream()
                    .map(EvaluationRunner::toObservedSource)
                    .toList();
            Map<String, Double> judgeScores = judgeScores(evaluationCase, assessment);
            Map<String, String> judgeReasons = judgeReasons(assessment);
            return new EvaluationObservation("1.0", evaluationCase.id(), sources,
                    result.answer(), assessment.decision() == RagAnswerJudgePort.Decision.REFUSED, citations,
                    judgeScores, judgeReasons, result.durationMs() + assessment.durationMs(), null);
        } catch (RuntimeException e) {
            String message = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return new EvaluationObservation("1.0", evaluationCase.id(), List.of(), null, null,
                    List.of(), Map.of(), Map.of(), elapsedMillis(startedAt), message);
        }
    }

    private static RagAnswerJudgePort.Request toJudgeRequest(EvaluationCase evaluationCase,
                                                              RagAnswerEvaluationPort.Result result) {
        List<RagAnswerJudgePort.Evidence> evidence = result.retrieved().stream()
                .map(hit -> new RagAnswerJudgePort.Evidence(hit.filename(), hit.content()))
                .toList();
        return new RagAnswerJudgePort.Request(evaluationCase.question(), evaluationCase.expected().shouldAnswer(),
                evaluationCase.expected().requiredFacts(), evaluationCase.expected().forbiddenFacts(),
                result.answer(), evidence);
    }

    private static Map<String, Double> judgeScores(EvaluationCase evaluationCase,
                                                   RagAnswerJudgePort.Result assessment) {
        Map<String, Double> scores = new LinkedHashMap<>();
        if (!evaluationCase.expected().requiredFacts().isEmpty()) {
            scores.put("requiredFactCoverage", matchedRatio(assessment.requiredFacts()));
        }
        if (!evaluationCase.expected().forbiddenFacts().isEmpty()) {
            scores.put("forbiddenFactSafety", 1.0 - matchedRatio(assessment.forbiddenFacts()));
        }
        boolean expectedRefusal = !evaluationCase.expected().shouldAnswer();
        boolean refused = assessment.decision() == RagAnswerJudgePort.Decision.REFUSED;
        scores.put("answerDecisionAccuracy", expectedRefusal == refused ? 1.0 : 0.0);
        scores.put("unsupportedClaimSafety", assessment.hasUnsupportedClaims() ? 0.0 : 1.0);
        return scores;
    }

    private static Map<String, String> judgeReasons(RagAnswerJudgePort.Result assessment) {
        Map<String, String> reasons = new LinkedHashMap<>();
        reasons.put("answerDecisionAccuracy", assessment.decisionReason());
        reasons.put("unsupportedClaimSafety", assessment.unsupportedClaimsReason());
        reasons.put("requiredFactCoverage", factReasons(assessment.requiredFacts()));
        reasons.put("forbiddenFactSafety", factReasons(assessment.forbiddenFacts()));
        return reasons;
    }

    private static double matchedRatio(List<RagAnswerJudgePort.FactAssessment> facts) {
        return facts.stream().filter(RagAnswerJudgePort.FactAssessment::matched).count() / (double) facts.size();
    }

    private static String factReasons(List<RagAnswerJudgePort.FactAssessment> facts) {
        return facts.stream()
                .map(fact -> fact.fact() + "：" + fact.reason())
                .collect(Collectors.joining("；"));
    }

    private static List<RagAnswerEvaluationPort.HistoryMessage> toHistory(
            List<EvaluationCase.HistoryMessage> history) {
        return history.stream()
                .map(message -> new RagAnswerEvaluationPort.HistoryMessage(
                        MessageType.valueOf(message.role().toUpperCase(java.util.Locale.ROOT)), message.content()))
                .toList();
    }

    private static EvaluationObservation.ObservedSource toObservedSource(KnowledgeSearchPort.KnowledgeHit hit) {
        return new EvaluationObservation.ObservedSource(hit.chunkId(), hit.fileId(), hit.filename(),
                hit.sheetName(), hit.sectionId(), hit.parentId(), hit.chunkIndex(), hit.content(), hit.score());
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}
