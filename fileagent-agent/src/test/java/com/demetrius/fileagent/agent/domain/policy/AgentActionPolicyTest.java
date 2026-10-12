package com.demetrius.fileagent.agent.domain.policy;

import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import com.demetrius.fileagent.api.enums.PolicyDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 动作风险策略的纯领域矩阵验证。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-12
 */
class AgentActionPolicyTest {

    private final AgentActionPolicy policy = new AgentActionPolicy(Set.of("managed_http_action"));

    @Test
    void shouldAllowAuthorizedReadOnlyActionWithoutOutboundModelContent() {
        AgentActionCandidate candidate = candidate(ActionRiskLevel.READ_ONLY, Map.of(), false);

        assertThat(policy.decide(candidate)).isEqualTo(PolicyDecision.ALLOW);
    }

    @Test
    void shouldRequireApprovalWhenReadOnlyActionExternalizesModelFreeText() {
        AgentActionCandidate candidate = candidate(ActionRiskLevel.READ_ONLY,
                Map.of("query", "模型生成的自由文本"), true);

        assertThat(policy.decide(candidate)).isEqualTo(PolicyDecision.ASK);
    }

    @Test
    void shouldRequireApprovalForEveryWriteRiskLevel() {
        assertThat(policy.decide(candidate(ActionRiskLevel.LOW_RISK_WRITE, Map.of(), false)))
                .isEqualTo(PolicyDecision.ASK);
        assertThat(policy.decide(candidate(ActionRiskLevel.HIGH_RISK_WRITE, Map.of(), false)))
                .isEqualTo(PolicyDecision.ASK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"actorId", "url", "credential"})
    void shouldDenyModelSuppliedSecurityBindingFields(String forbiddenField) {
        AgentActionCandidate candidate = candidate(ActionRiskLevel.HIGH_RISK_WRITE,
                Map.of(forbiddenField, "model-controlled"), false);

        assertThat(policy.decide(candidate)).isEqualTo(PolicyDecision.DENY);
    }

    @Test
    void shouldDenyUnknownToolOrResourceOutsideTrustedScope() {
        AgentActionCandidate unknownTool = candidate("unknown_tool", "resource:allowed",
                ActionRiskLevel.READ_ONLY, Map.of(), false);
        AgentActionCandidate unauthorizedResource = candidate("managed_http_action", "resource:outside",
                ActionRiskLevel.READ_ONLY, Map.of(), false);

        assertThat(policy.decide(unknownTool)).isEqualTo(PolicyDecision.DENY);
        assertThat(policy.decide(unauthorizedResource)).isEqualTo(PolicyDecision.DENY);
    }

    private AgentActionCandidate candidate(ActionRiskLevel riskLevel, Map<String, Object> modelArguments,
                                           boolean externalizesModelFreeText) {
        return candidate("managed_http_action", "resource:allowed", riskLevel,
                modelArguments, externalizesModelFreeText);
    }

    private AgentActionCandidate candidate(String toolName, String resourceScope,
                                           ActionRiskLevel riskLevel, Map<String, Object> modelArguments,
                                           boolean externalizesModelFreeText) {
        return new AgentActionCandidate(
                "run-1",
                "tool-call-1",
                toolName,
                "tool-v1",
                "schema-v1",
                "endpoint-v1",
                "endpoint-hash",
                "policy-v1",
                riskLevel,
                resourceScope,
                Set.of("resource:allowed"),
                modelArguments,
                externalizesModelFreeText,
                "{\"operation\":\"demo\"}",
                "input-hmac",
                "operation-1",
                "执行受控操作",
                "影响一条演示记录",
                "{\"name\":\"***\"}");
    }
}
