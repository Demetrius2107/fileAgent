package com.demetrius.fileagent.evaluation;

import java.util.List;

/**
 * 部署实例返回的完整评测结果。
 *
 * @author raosaijie
 */
public record EvaluationRunResponse(
        EvaluationReport report,
        List<EvaluationObservation> observations,
        String markdown
) {

    public EvaluationRunResponse {
        observations = observations == null ? List.of() : List.copyOf(observations);
    }
}
