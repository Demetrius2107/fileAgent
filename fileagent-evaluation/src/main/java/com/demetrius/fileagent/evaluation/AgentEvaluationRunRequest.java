package com.demetrius.fileagent.evaluation;

/**
 * 部署实例执行 Agent 端到端评测的请求。
 *
 * @author raosaijie
 */
public record AgentEvaluationRunRequest(
        String datasetVersion,
        AgentEvaluationReport baseline
) {

    public AgentEvaluationRunRequest {
        datasetVersion = datasetVersion == null || datasetVersion.isBlank() ? "agent-v1" : datasetVersion;
    }

    public static AgentEvaluationRunRequest defaults() {
        return new AgentEvaluationRunRequest("agent-v1", null);
    }
}
