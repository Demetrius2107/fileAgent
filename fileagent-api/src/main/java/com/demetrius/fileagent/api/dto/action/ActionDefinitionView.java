package com.demetrius.fileagent.api.dto.action;

import com.demetrius.fileagent.api.enums.ActionRiskLevel;

import java.util.Map;
import java.util.Set;

/**
 * 动作定义的无凭证快照，供 Agent 在审批前完成参数、风险和资源校验。
 *
 * @author raosaijie
 */
public record ActionDefinitionView(
        String actionId,
        String displayName,
        String toolVersion,
        String definitionVersion,
        String endpointRevision,
        String endpointConfigHash,
        String policyVersion,
        Map<String, Object> argumentSchema,
        ActionRiskLevel riskLevel,
        String resourceScope,
        Set<String> modelWritableFields,
        boolean externalizesModelFreeText,
        String safeSummary,
        String expectedImpact) {

    public ActionDefinitionView {
        argumentSchema = Map.copyOf(argumentSchema == null ? Map.of() : argumentSchema);
        modelWritableFields = Set.copyOf(modelWritableFields == null ? Set.of() : modelWritableFields);
    }
}
