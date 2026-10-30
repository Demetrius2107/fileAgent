package com.demetrius.fileagent.agent.infrastructure.tool;

import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import com.demetrius.fileagent.api.dto.AgentRunCommand;
import com.demetrius.fileagent.api.dto.action.ActionDefinitionView;
import com.demetrius.fileagent.api.dto.action.ActionExecutionCommand;
import com.demetrius.fileagent.api.dto.action.ActionExecutionView;
import com.demetrius.fileagent.api.dto.action.PythonSandboxActionDefinition;
import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import com.demetrius.fileagent.api.enums.ActionExecutionStatus;
import com.demetrius.fileagent.api.port.PythonSandboxExecutionPort;
import com.demetrius.fileagent.common.security.EnvelopeCrypto;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * execute_python Agent 工具的输入边界、审批绑定和执行命令测试。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
class ExecutePythonToolTest {

    private PythonSandboxExecutionPort sandboxExecutionPort;
    private EnvelopeCrypto envelopeCrypto;
    private ExecutePythonTool tool;

    @BeforeEach
    void setUp() {
        sandboxExecutionPort = mock(PythonSandboxExecutionPort.class);
        envelopeCrypto = mock(EnvelopeCrypto.class);
        when(envelopeCrypto.hmac(anyString())).thenAnswer(invocation ->
                "hmac-" + Integer.toUnsignedString(invocation.<String>getArgument(0).hashCode(), 16));
        when(sandboxExecutionPort.definition()).thenReturn(PythonSandboxActionDefinition.definition());
        when(sandboxExecutionPort.execute(any())).thenReturn(new ActionExecutionView(
                "execution-1", "operation-1", ActionExecutionStatus.SUCCEEDED,
                "PYTHON_EXECUTION_SUCCEEDED", "Python 脚本执行成功，标准输出：\n3", false,
                Instant.parse("2026-10-27T04:00:00Z"), Instant.parse("2026-10-27T04:00:01Z")));
        tool = new ExecutePythonTool(
                "run-1", "local-operator", Set.of(PythonSandboxActionDefinition.RESOURCE_SCOPE),
                sandboxExecutionPort, new ObjectMapper(), envelopeCrypto,
                PythonSandboxActionDefinition.definition());
    }

    @Test
    void modelSchemaShouldExposeOnlyCodeAndInputAndRejectTrustedFields() {
        assertThat(tool.getParameters()).satisfies(schema -> {
            assertThat(schema.get("additionalProperties")).isEqualTo(false);
            assertThat(schema.get("required")).isEqualTo(List.of("code", "input"));
            assertThat(((Map<?, ?>) schema.get("properties")).keySet().stream()
                    .map(String::valueOf).toList()).containsExactlyInAnyOrder("code", "input");
        });

        Map<String, Object> injected = new LinkedHashMap<>(input());
        injected.put("url", "https://attacker.example");
        injected.put("image", "attacker/image");
        injected.put("network", "host");
        injected.put("authorization", "Bearer stolen");

        assertThat(tool.checkPermissions(injected, PermissionContextState.builder().build()).block()
                .getBehavior()).isEqualTo(PermissionBehavior.DENY);
        verify(sandboxExecutionPort, never()).execute(any());
    }

    @Test
    void factoryShouldExposeOnlyWhenPythonScopeIsAuthorized() {
        AgentProperties properties = new AgentProperties();
        properties.setAuthorizedActionResourceScopes(Set.of(PythonSandboxActionDefinition.RESOURCE_SCOPE));
        ExecutePythonToolFactory factory = new ExecutePythonToolFactory(
                properties, sandboxExecutionPort, new ObjectMapper(), envelopeCrypto);
        AgentRunCommand command = mock(AgentRunCommand.class);
        when(command.runId()).thenReturn("run-1");

        assertThat(factory.available(command)).isTrue();
        assertThat(factory.create(command).getDescription())
                .contains("隔离", "无网络", "code", "input")
                .doesNotContain("Worker", "Podman", "镜像");

        properties.setAuthorizedActionResourceScopes(Set.of("other:scope"));
        assertThat(factory.available(command)).isFalse();
    }

    @Test
    void shouldRequireApprovalAndOnlyExecuteTrustedBoundCommand() {
        assertThat(tool.checkPermissions(input(), PermissionContextState.builder().build()).block()
                .getBehavior()).isEqualTo(PermissionBehavior.ASK);

        StepVerifier.create(tool.callAsync(param("tool-call-1")))
                .assertNext(result -> assertThat(((TextBlock) result.getOutput().getFirst()).getText())
                        .contains("Python 脚本执行成功", "execution-1"))
                .verifyComplete();

        ArgumentCaptor<ActionExecutionCommand> command = ArgumentCaptor.forClass(ActionExecutionCommand.class);
        verify(sandboxExecutionPort).execute(command.capture());
        assertThat(command.getValue()).satisfies(value -> {
            assertThat(value.toolName()).isEqualTo(PythonSandboxActionDefinition.TOOL_NAME);
            assertThat(value.actionId()).isEqualTo(PythonSandboxActionDefinition.ACTION_ID);
            assertThat(value.authorizedResourceScopes()).containsExactly(PythonSandboxActionDefinition.RESOURCE_SCOPE);
            assertThat(value.canonicalArguments())
                    .isEqualTo("{\"code\":\"print(input_data['x'])\",\"input\":{\"x\":3}}");
        });
        assertThat(tool.takeCompletionNotice("tool-call-1")).satisfies(notice -> {
            assertThat(notice.code()).isEqualTo("SUCCEEDED");
            assertThat(notice.message()).contains("Python 脚本执行完成", "execution-1");
        });
    }

    @Test
    void shouldBoundApprovalPreviewButKeepFullCanonicalArgumentsForBinding() {
        String largeCode = "# ".repeat(3_000) + "print(input_data['x'])";
        Map<String, Object> largeInput = Map.of("code", largeCode, "input", Map.of("x", 3));
        ToolUseBlock toolCall = new ToolUseBlock("tool-call-preview", PythonSandboxActionDefinition.TOOL_NAME,
                largeInput, null, Map.of(), ToolCallState.PENDING);

        assertThat(tool.checkPermissions(largeInput, PermissionContextState.builder().build()).block()
                .getBehavior()).isEqualTo(PermissionBehavior.ASK);

        assertThat(tool.approvalCandidate(toolCall)).satisfies(candidate -> {
            assertThat(candidate.canonicalArguments()).hasSizeGreaterThan(
                    ExecutePythonTool.MAX_PARAMETER_PREVIEW_CHARACTERS);
            assertThat(candidate.parameterPreview())
                    .hasSizeLessThanOrEqualTo(ExecutePythonTool.MAX_PARAMETER_PREVIEW_CHARACTERS)
                    .contains("审批预览已截断", "完整内容由指纹绑定");
            assertThat(candidate.parameterPreview()).isNotEqualTo(candidate.canonicalArguments());
        });
    }

    @Test
    void shouldDenyWhenSandboxDefinitionDriftsAwayFromHighRiskApproval() {
        ActionDefinitionView fixed = PythonSandboxActionDefinition.definition();
        when(sandboxExecutionPort.definition()).thenReturn(new ActionDefinitionView(
                fixed.actionId(), fixed.displayName(), fixed.toolVersion(), fixed.definitionVersion(),
                fixed.endpointRevision(), fixed.endpointConfigHash(), fixed.policyVersion(), fixed.argumentSchema(),
                ActionRiskLevel.READ_ONLY, fixed.resourceScope(), fixed.modelWritableFields(),
                fixed.externalizesModelFreeText(), fixed.safeSummary(), fixed.expectedImpact()));

        assertThat(tool.checkPermissions(input(), PermissionContextState.builder().build()).block()
                .getBehavior()).isEqualTo(PermissionBehavior.DENY);
        verify(sandboxExecutionPort, never()).execute(any());
    }

    private static Map<String, Object> input() {
        return Map.of("code", "print(input_data['x'])", "input", Map.of("x", 3));
    }

    private static ToolCallParam param(String toolCallId) {
        return ToolCallParam.builder()
                .toolUseBlock(toolCall(toolCallId, ToolCallState.ALLOWED))
                .input(input())
                .runtimeContext(RuntimeContext.builder()
                        .sessionId("run-1")
                        .userId("local-operator")
                        .build())
                .build();
    }

    private static ToolUseBlock toolCall(String toolCallId, ToolCallState state) {
        return new ToolUseBlock(toolCallId, PythonSandboxActionDefinition.TOOL_NAME,
                input(), null, Map.of(), state);
    }
}
