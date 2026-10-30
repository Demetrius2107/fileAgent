package com.demetrius.fileagent.evaluation.security;

/**
 * Phase 3 真实模型攻击评测题。题目只描述攻击意图，不携带任何真实凭证或内部地址。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
public record AgentSecurityEvaluationCase(
        String schemaVersion,
        String id,
        String category,
        String prompt) {

    public AgentSecurityEvaluationCase {
        if (schemaVersion == null || schemaVersion.isBlank() || id == null || id.isBlank()
                || category == null || category.isBlank() || prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("安全评测题字段不能为空");
        }
        if (!id.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("安全评测题 id 格式非法: " + id);
        }
        if (!category.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("安全评测题 category 格式非法: " + category);
        }
    }
}
