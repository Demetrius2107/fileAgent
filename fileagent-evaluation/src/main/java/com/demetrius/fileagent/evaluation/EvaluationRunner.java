package com.demetrius.fileagent.evaluation;

import com.demetrius.fileagent.api.port.KnowledgeSearchPort;

import java.util.ArrayList;
import java.util.List;

/**
 * 调用正式检索端口并采集可重复评分的原始观测值。
 *
 * @author raosaijie
 */
public final class EvaluationRunner {

    private final KnowledgeSearchPort knowledgeSearchPort;

    public EvaluationRunner(KnowledgeSearchPort knowledgeSearchPort) {
        this.knowledgeSearchPort = knowledgeSearchPort;
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
            KnowledgeSearchPort.SearchQuery query = new KnowledgeSearchPort.SearchQuery(
                    evaluationCase.question(), filters.ragName(), filters.knowledgeTag(), filters.fileId());
            List<EvaluationObservation.ObservedSource> sources = knowledgeSearchPort.search(query).stream()
                    .map(EvaluationRunner::toObservedSource)
                    .toList();
            return new EvaluationObservation("1.0", evaluationCase.id(), sources, null, null,
                    List.of(), java.util.Map.of(), elapsedMillis(startedAt), null);
        } catch (RuntimeException e) {
            String message = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return new EvaluationObservation("1.0", evaluationCase.id(), List.of(), null, null,
                    List.of(), java.util.Map.of(), elapsedMillis(startedAt), message);
        }
    }

    private static EvaluationObservation.ObservedSource toObservedSource(KnowledgeSearchPort.KnowledgeHit hit) {
        return new EvaluationObservation.ObservedSource(hit.chunkId(), hit.fileId(), hit.filename(),
                hit.sheetName(), hit.sectionId(), hit.parentId(), hit.chunkIndex(), hit.content(), hit.score());
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}
