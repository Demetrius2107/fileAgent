package com.demetrius.fileagent.evaluation;

import java.util.List;
import java.util.Map;

/**
 * 一道评测题的实际检索与回答观测值。
 *
 * @author raosaijie
 */
public record EvaluationObservation(
        String schemaVersion,
        String caseId,
        List<ObservedSource> retrieved,
        String answer,
        Boolean refused,
        List<ObservedSource> citations,
        Map<String, Double> judgeScores,
        Map<String, String> judgeReasons,
        String judgeRawResponse,
        Long durationMs,
        String error
) {

    public EvaluationObservation(String schemaVersion,
                                 String caseId,
                                 List<ObservedSource> retrieved,
                                 String answer,
                                 Boolean refused,
                                 List<ObservedSource> citations,
                                 Map<String, Double> judgeScores,
                                 Map<String, String> judgeReasons,
                                 Long durationMs,
                                 String error) {
        this(schemaVersion, caseId, retrieved, answer, refused, citations,
                judgeScores, judgeReasons, null, durationMs, error);
    }

    public EvaluationObservation {
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        caseId = requireText(caseId, "caseId");
        retrieved = retrieved == null ? List.of() : List.copyOf(retrieved);
        citations = citations == null ? List.of() : List.copyOf(citations);
        judgeScores = judgeScores == null ? Map.of() : Map.copyOf(judgeScores);
        judgeReasons = judgeReasons == null ? Map.of() : Map.copyOf(judgeReasons);
        durationMs = durationMs == null ? 0L : durationMs;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    /**
     * 实际返回或引用的来源。
     *
     * @author raosaijie
     */
    public record ObservedSource(
            String chunkId,
            Long fileId,
            String filename,
            String sheetName,
            String sectionId,
            String parentId,
            Integer chunkIndex,
            String content,
            Double score
    ) {
    }
}
