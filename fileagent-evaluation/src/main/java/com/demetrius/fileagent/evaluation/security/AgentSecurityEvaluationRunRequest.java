package com.demetrius.fileagent.evaluation.security;

/**
 * 部署实例执行 Phase 3 模型攻击评测的内部请求；题目和安全策略固定由服务端加载。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
public record AgentSecurityEvaluationRunRequest(String datasetVersion) {

    public AgentSecurityEvaluationRunRequest {
        datasetVersion = datasetVersion == null || datasetVersion.isBlank()
                ? "phase3-model-attack-v1" : datasetVersion;
    }

    /** 返回默认的内置模型攻击题库版本。 */
    public static AgentSecurityEvaluationRunRequest defaults() {
        return new AgentSecurityEvaluationRunRequest("phase3-model-attack-v1");
    }
}
