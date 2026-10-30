package com.demetrius.fileagent.agent.infrastructure.tool;

import com.demetrius.fileagent.api.dto.action.ActionDefinitionView;
import com.demetrius.fileagent.api.dto.action.PythonSandboxActionDefinition;
import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import com.demetrius.fileagent.api.port.PythonSandboxExecutionPort;
import com.demetrius.fileagent.common.security.EnvelopeCrypto;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 3 审批与可信上下文门禁：模型参数不得改变 Python Sandbox 的安全定义。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
class Phase3ApprovalSecurityTest {

    private PythonSandboxExecutionPort sandboxExecutionPort;
    private ExecutePythonTool tool;

    @BeforeEach
    void setUp() {
        sandboxExecutionPort = mock(PythonSandboxExecutionPort.class);
        when(sandboxExecutionPort.definition()).thenReturn(PythonSandboxActionDefinition.definition());
        EnvelopeCrypto crypto = mock(EnvelopeCrypto.class);
        when(crypto.hmac(anyString())).thenAnswer(invocation -> "hmac-" + invocation.<String>getArgument(0));
        tool = new ExecutePythonTool("phase3-run", "local-operator",
                Set.of(PythonSandboxActionDefinition.RESOURCE_SCOPE), sandboxExecutionPort,
                new ObjectMapper(), crypto, PythonSandboxActionDefinition.definition());
    }

    @Test
    void shouldRequireExplicitApprovalAndRejectInjectedRuntimeControls() {
        assertThat(tool.checkPermissions(validInput(), PermissionContextState.builder().build()).block()
                .getBehavior()).isEqualTo(PermissionBehavior.ASK);

        Map<String, Object> injected = new LinkedHashMap<>(validInput());
        injected.put("url", "https://attacker.example/run");
        injected.put("image", "attacker/python:latest");
        injected.put("network", "host");
        injected.put("mount", "/:/host");

        assertThat(tool.checkPermissions(injected, PermissionContextState.builder().build()).block()
                .getBehavior()).isEqualTo(PermissionBehavior.DENY);
    }

    @Test
    void shouldFailClosedWhenFixedHighRiskDefinitionDrifts() {
        ActionDefinitionView fixed = PythonSandboxActionDefinition.definition();
        when(sandboxExecutionPort.definition()).thenReturn(new ActionDefinitionView(
                fixed.actionId(), fixed.displayName(), fixed.toolVersion(), fixed.definitionVersion(),
                fixed.endpointRevision(), fixed.endpointConfigHash(), fixed.policyVersion(), fixed.argumentSchema(),
                ActionRiskLevel.READ_ONLY, fixed.resourceScope(), fixed.modelWritableFields(),
                fixed.externalizesModelFreeText(), fixed.safeSummary(), fixed.expectedImpact()));

        assertThat(tool.checkPermissions(validInput(), PermissionContextState.builder().build()).block()
                .getBehavior()).isEqualTo(PermissionBehavior.DENY);
    }

    private static Map<String, Object> validInput() {
        return Map.of("code", "print(input_data['value'])", "input", Map.of("value", 1));
    }
}
