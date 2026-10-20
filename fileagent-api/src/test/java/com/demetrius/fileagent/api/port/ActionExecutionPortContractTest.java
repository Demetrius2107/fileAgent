package com.demetrius.fileagent.api.port;

import com.demetrius.fileagent.api.dto.action.ActionDefinitionView;
import com.demetrius.fileagent.api.dto.action.ActionExecutionCommand;
import com.demetrius.fileagent.api.enums.ActionCancelStatus;
import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动作执行跨域契约测试，防止凭证、目标地址或可变授权上下文泄漏到 Agent 域。
 *
 * @author raosaijie
 */
class ActionExecutionPortContractTest {

    @Test
    void definitionShouldExposeOnlyApprovalAndValidationMetadata() {
        Set<String> componentNames = Arrays.stream(ActionDefinitionView.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(componentNames)
                .doesNotContain("baseurl", "credential", "credentials", "credentialref",
                        "headers", "internalheaders", "authorization");
    }

    @Test
    void definitionAndCommandShouldDefensivelyCopyMutableInputs() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Set<String> modelFields = new LinkedHashSet<>(Set.of("amount"));
        ActionDefinitionView definition = new ActionDefinitionView(
                "create-expense-draft", "创建报销草稿", "http-action-v1", "definition-v1",
                "endpoint-v1", "config-hmac", "policy-v1", schema,
                ActionRiskLevel.LOW_RISK_WRITE, "expense:draft", modelFields,
                true, "创建报销草稿", "新增一条草稿记录");

        Set<String> authorizedScopes = new LinkedHashSet<>(Set.of("expense:draft"));
        ActionExecutionCommand command = new ActionExecutionCommand(
                "run-1", "tool-call-1", "call_registered_http_api", "http-action-v1",
                "create-expense-draft", "definition-v1", "endpoint-v1", "config-hmac",
                "policy-v1", "execution-key", "operation-fingerprint", "operation-id",
                "{\"amount\":100}", "input-hmac", "local-operator",
                authorizedScopes, "expense:draft");

        schema.put("unexpected", true);
        modelFields.add("token");
        authorizedScopes.add("expense:admin");

        assertThat(definition.argumentSchema()).containsOnlyKeys("type");
        assertThat(definition.modelWritableFields()).containsExactly("amount");
        assertThat(command.authorizedResourceScopes()).containsExactly("expense:draft");
    }

    @Test
    void cancellationShouldHaveOnlyConservativeOutcomes() {
        assertThat(ActionCancelStatus.values())
                .containsExactly(ActionCancelStatus.NOT_STARTED,
                        ActionCancelStatus.CANCELLED,
                        ActionCancelStatus.OUTCOME_UNKNOWN);
    }
}
