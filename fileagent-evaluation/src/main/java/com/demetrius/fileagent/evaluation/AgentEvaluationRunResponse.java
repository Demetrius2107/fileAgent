package com.demetrius.fileagent.evaluation;

import java.util.List;

/**
 * 部署实例返回的 Agent 端到端评测结果。
 *
 * @author raosaijie
 */
public record AgentEvaluationRunResponse(
        AgentEvaluationReport report,
        List<AgentEvaluationObservation> observations,
        String markdown
) {

    public AgentEvaluationRunResponse {
        observations = observations == null ? List.of() : List.copyOf(observations);
    }
}
