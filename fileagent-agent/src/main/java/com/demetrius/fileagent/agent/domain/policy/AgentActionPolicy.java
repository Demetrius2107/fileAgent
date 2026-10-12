package com.demetrius.fileagent.agent.domain.policy;

import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import com.demetrius.fileagent.api.enums.PolicyDecision;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Agent 动作的纯领域风险策略：只接受服务端构造的候选快照，模型无权指定身份、端点或凭据。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-12
 */
public final class AgentActionPolicy {

    private static final Set<String> PROHIBITED_MODEL_BINDING_KEYS = Set.of(
            "actorid", "userid", "tenantid", "role", "roles",
            "url", "uri", "endpoint", "baseurl", "host", "hostname",
            "credential", "credentials", "apikey", "authorization", "token", "password", "secret",
            "resourcescope", "endpointrevision", "endpointconfighash", "policyversion");

    private final Set<String> registeredToolNames;

    public AgentActionPolicy(Set<String> registeredToolNames) {
        this.registeredToolNames = Set.copyOf(registeredToolNames == null ? Set.of() : registeredToolNames);
    }

    /**
     * 对候选动作进行确定性决策。任何不完整、越权或由模型控制安全边界的候选均直接拒绝。
     */
    public PolicyDecision decide(AgentActionCandidate candidate) {
        if (!hasValidTrustedBinding(candidate)
                || !registeredToolNames.contains(candidate.toolName())
                || !candidate.authorizedResourceScopes().contains(candidate.resourceScope())
                || containsProhibitedBindingField(candidate.modelArguments())) {
            return PolicyDecision.DENY;
        }
        if (candidate.riskLevel() == ActionRiskLevel.READ_ONLY) {
            return candidate.externalizesModelFreeText() ? PolicyDecision.ASK : PolicyDecision.ALLOW;
        }
        return PolicyDecision.ASK;
    }

    private static boolean hasValidTrustedBinding(AgentActionCandidate candidate) {
        return candidate != null
                && hasText(candidate.runId())
                && hasText(candidate.toolCallId())
                && hasText(candidate.toolName())
                && hasText(candidate.toolVersion())
                && hasText(candidate.toolDefinitionVersion())
                && hasText(candidate.endpointRevision())
                && hasText(candidate.endpointConfigHash())
                && hasText(candidate.policyVersion())
                && candidate.riskLevel() != null
                && hasText(candidate.resourceScope())
                && hasText(candidate.canonicalArguments())
                && hasText(candidate.canonicalInputHmac())
                && hasText(candidate.operationId())
                && hasText(candidate.safeSummary())
                && hasText(candidate.expectedImpact())
                && hasText(candidate.parameterPreview());
    }

    private static boolean containsProhibitedBindingField(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (isProhibitedBindingKey(String.valueOf(entry.getKey()))
                        || containsProhibitedBindingField(entry.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Collection<?> values) {
            return values.stream().anyMatch(AgentActionPolicy::containsProhibitedBindingField);
        }
        return false;
    }

    private static boolean isProhibitedBindingKey(String key) {
        if (!hasText(key)) {
            return false;
        }
        String normalized = key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return PROHIBITED_MODEL_BINDING_KEYS.contains(normalized);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
