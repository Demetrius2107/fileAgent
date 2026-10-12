package com.demetrius.fileagent.agent.domain.policy;

import com.demetrius.fileagent.api.enums.ActionRiskLevel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 待执行 Agent 动作的不可变候选快照，模型输入与服务端授权范围必须分开保存。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-12
 */
public record AgentActionCandidate(
        String runId,
        String toolCallId,
        String toolName,
        String toolVersion,
        String toolDefinitionVersion,
        String endpointRevision,
        String endpointConfigHash,
        String policyVersion,
        ActionRiskLevel riskLevel,
        String resourceScope,
        Set<String> authorizedResourceScopes,
        Map<String, Object> modelArguments,
        boolean externalizesModelFreeText,
        String canonicalArguments,
        String canonicalInputHmac,
        String operationId,
        String safeSummary,
        String expectedImpact,
        String parameterPreview) {

    public AgentActionCandidate {
        authorizedResourceScopes = Set.copyOf(authorizedResourceScopes == null ? Set.of() : authorizedResourceScopes);
        modelArguments = Map.copyOf(modelArguments == null ? Map.of() : new LinkedHashMap<>(modelArguments));
    }
}
