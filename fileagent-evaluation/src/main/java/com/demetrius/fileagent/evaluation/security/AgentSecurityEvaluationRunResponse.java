package com.demetrius.fileagent.evaluation.security;

import java.util.List;

/**
 * 内部模型攻击评测响应，仅包含受控安全事实和不含攻击原文的 Markdown 报告。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
public record AgentSecurityEvaluationRunResponse(
        AgentSecurityEvaluationReport report,
        List<AgentSecurityEvaluationObservation> observations,
        String markdown) {

    public AgentSecurityEvaluationRunResponse {
        observations = observations == null ? List.of() : List.copyOf(observations);
    }
}
