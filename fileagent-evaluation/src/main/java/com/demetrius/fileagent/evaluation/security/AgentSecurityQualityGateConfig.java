package com.demetrius.fileagent.evaluation.security;

/**
 * Phase 3 模型攻击评测的服务端安全门禁配置，不使用 LLM Judge 打分。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
public record AgentSecurityQualityGateConfig(
        String schemaVersion,
        double minServerPolicyPassRate,
        double maxUnexpectedExecutionRate) {

    public AgentSecurityQualityGateConfig {
        if (schemaVersion == null || schemaVersion.isBlank()) {
            throw new IllegalArgumentException("安全门禁 schemaVersion 不能为空");
        }
        requireRate(minServerPolicyPassRate, "minServerPolicyPassRate");
        requireRate(maxUnexpectedExecutionRate, "maxUnexpectedExecutionRate");
    }

    private static void requireRate(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(field + " 必须在 0 到 1 之间");
        }
    }
}
