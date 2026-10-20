package com.demetrius.fileagent.agent.infrastructure.tool;

import com.demetrius.fileagent.api.dto.action.ActionDefinitionView;
import com.demetrius.fileagent.api.dto.action.ActionExecutionCommand;
import com.demetrius.fileagent.api.dto.action.ActionExecutionView;
import com.demetrius.fileagent.api.enums.ActionExecutionStatus;
import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import com.demetrius.fileagent.api.port.ActionExecutionPort;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 注册式 HTTP Agent 工具的输入边界、审批绑定和幂等标识测试。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-20
 */
class CallRegisteredHttpApiToolTest {

    private ActionExecutionPort actionExecutionPort;
    private EnvelopeCrypto envelopeCrypto;
    private CallRegisteredHttpApiTool tool;

    @BeforeEach
    void setUp() {
        actionExecutionPort = mock(ActionExecutionPort.class);
        envelopeCrypto = mock(EnvelopeCrypto.class);
        when(envelopeCrypto.hmac(anyString())).thenAnswer(invocation ->
                "hmac-" + Integer.toUnsignedString(invocation.<String>getArgument(0).hashCode(), 16));
        when(actionExecutionPort.resolveDefinition("create-expense-draft")).thenReturn(definition("endpoint-v1"));
        when(actionExecutionPort.execute(any())).thenReturn(new ActionExecutionView(
                "execution-1", "operation-1", ActionExecutionStatus.SUCCEEDED,
                "HTTP_200", "{\"draftId\":\"D-1\"}", false,
                Instant.parse("2026-10-20T08:00:00Z"), Instant.parse("2026-10-20T08:00:01Z")));
        tool = new CallRegisteredHttpApiTool(
                "run-1", "local-operator", Set.of("expense:draft"),
                actionExecutionPort, new ObjectMapper(), envelopeCrypto);
    }

    @Test
    void modelSchemaShouldExposeOnlyEndpointIdAndArgumentsAndRejectTrustedFields() {
        assertThat(tool.getParameters()).satisfies(schema -> {
            assertThat(schema.get("additionalProperties")).isEqualTo(false);
            assertThat(schema.get("required")).isEqualTo(List.of("endpointId", "arguments"));
            assertThat(((Map<?, ?>) schema.get("properties")).keySet().stream()
                    .map(String::valueOf).toList())
                    .containsExactlyInAnyOrder("endpointId", "arguments");
        });

        Map<String, Object> injected = new LinkedHashMap<>(input(100, "出差", "secret-value"));
        injected.put("url", "https://attacker.example");
        injected.put("method", "DELETE");
        injected.put("authorization", "Bearer stolen");
        injected.put("actorId", "admin");

        assertThat(tool.checkPermissions(injected, PermissionContextState.builder().build()).block()
                .getBehavior()).isEqualTo(PermissionBehavior.DENY);
        verify(actionExecutionPort, never()).execute(any());
    }

    @Test
    void shouldValidateEndpointSchemaAndRedactSensitivePreviewBeforeApproval() {
        assertThat(tool.checkPermissions(input("not-a-number", "出差", "secret-value"),
                PermissionContextState.builder().build()).block().getBehavior())
                .isEqualTo(PermissionBehavior.DENY);

        assertThat(tool.checkPermissions(input(100, "出差", "secret-value"),
                PermissionContextState.builder().build()).block().getBehavior())
                .isEqualTo(PermissionBehavior.ASK);
        var candidate = tool.approvalCandidate(toolCall("tool-call-1", ToolCallState.PENDING));

        assertThat(candidate.parameterPreview())
                .contains("100", "出差", "[REDACTED]")
                .doesNotContain("secret-value");
        assertThat(candidate.safeSummary()).contains("创建报销草稿");
    }

    @Test
    void shouldRejectExecutionWhenEndpointBindingChangesAfterPermissionCheck() {
        when(actionExecutionPort.resolveDefinition("create-expense-draft"))
                .thenReturn(definition("endpoint-v1"), definition("endpoint-v2"));
        assertThat(tool.checkPermissions(input(100, "出差", "secret-value"),
                PermissionContextState.builder().build()).block().getBehavior())
                .isEqualTo(PermissionBehavior.ASK);

        StepVerifier.create(tool.callAsync(param("tool-call-1")))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("动作绑定已变化"))
                .verify();

        verify(actionExecutionPort, never()).execute(any());
    }

    @Test
    void shouldRejectExecutionWhenCanonicalHmacChangesAfterPermissionCheck() {
        AtomicInteger canonicalHashCalls = new AtomicInteger();
        when(envelopeCrypto.hmac(anyString())).thenAnswer(invocation -> {
            String value = invocation.getArgument(0);
            if (value.startsWith("{")) {
                return "input-hmac-v" + canonicalHashCalls.incrementAndGet();
            }
            return "hmac-" + Integer.toUnsignedString(value.hashCode(), 16);
        });
        assertThat(tool.checkPermissions(input(100, "出差", "secret-value"),
                PermissionContextState.builder().build()).block().getBehavior())
                .isEqualTo(PermissionBehavior.ASK);

        StepVerifier.create(tool.callAsync(param("tool-call-1")))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("动作绑定已变化"))
                .verify();

        verify(actionExecutionPort, never()).execute(any());
    }

    @Test
    void outcomeUnknownShouldRequireManualVerificationAndNeverClaimSuccess() {
        when(actionExecutionPort.execute(any())).thenReturn(new ActionExecutionView(
                "execution-unknown", "operation-unknown", ActionExecutionStatus.OUTCOME_UNKNOWN,
                "HTTP_OUTCOME_UNKNOWN", "不应原样输出", false,
                Instant.parse("2026-10-20T08:00:00Z"), Instant.parse("2026-10-20T08:00:01Z")));
        assertThat(tool.checkPermissions(input(100, "出差", "secret-value"),
                PermissionContextState.builder().build()).block().getBehavior())
                .isEqualTo(PermissionBehavior.ASK);

        StepVerifier.create(tool.callAsync(param("tool-call-1")))
                .assertNext(result -> assertThat(((TextBlock) result.getOutput().getFirst()).getText())
                        .contains("外部操作可能已执行", "execution-unknown", "禁止自动重试")
                        .doesNotContain("执行成功", "不应原样输出"))
                .verifyComplete();
    }

    @Test
    void sameSemanticCallsShouldReuseOperationButKeepDistinctExecutionKeys() {
        runApproved("tool-call-1");
        runApproved("tool-call-2");

        ArgumentCaptor<ActionExecutionCommand> captor = ArgumentCaptor.forClass(ActionExecutionCommand.class);
        verify(actionExecutionPort, org.mockito.Mockito.times(2)).execute(captor.capture());
        ActionExecutionCommand first = captor.getAllValues().get(0);
        ActionExecutionCommand second = captor.getAllValues().get(1);

        assertThat(first.executionKey()).isNotEqualTo(second.executionKey());
        assertThat(first.operationFingerprint()).isEqualTo(second.operationFingerprint());
        assertThat(first.operationId()).isEqualTo(second.operationId());
        assertThat(first.actorId()).isEqualTo("local-operator");
        assertThat(first.authorizedResourceScopes()).containsExactly("expense:draft");
        assertThat(first.canonicalArguments())
                .isEqualTo("{\"amount\":100,\"cardNumber\":\"secret-value\",\"memo\":\"出差\"}");
    }

    private void runApproved(String toolCallId) {
        assertThat(tool.checkPermissions(input(100, "出差", "secret-value"),
                PermissionContextState.builder().build()).block().getBehavior())
                .isEqualTo(PermissionBehavior.ASK);
        StepVerifier.create(tool.callAsync(param(toolCallId)))
                .assertNext(result -> assertThat(result.getOutput()).isNotEmpty())
                .verifyComplete();
    }

    private static Map<String, Object> input(Object amount, String memo, String token) {
        return Map.of(
                "endpointId", "create-expense-draft",
                "arguments", Map.of("amount", amount, "memo", memo, "cardNumber", token));
    }

    private static ToolCallParam param(String toolCallId) {
        return ToolCallParam.builder()
                .toolUseBlock(toolCall(toolCallId, ToolCallState.ALLOWED))
                .input(input(100, "出差", "secret-value"))
                .runtimeContext(RuntimeContext.builder()
                        .sessionId("run-1")
                        .userId("local-operator")
                        .build())
                .build();
    }

    private static ToolUseBlock toolCall(String toolCallId, ToolCallState state) {
        return new ToolUseBlock(toolCallId, "call_registered_http_api",
                input(100, "出差", "secret-value"), null, Map.of(), state);
    }

    private static ActionDefinitionView definition(String endpointRevision) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("amount", Map.of("type", "number"));
        properties.put("memo", Map.of("type", "string", "maxLength", 100));
        properties.put("cardNumber", Map.of("type", "string", "maxLength", 100, "sensitive", true));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("properties", properties);
        schema.put("required", List.of("amount", "memo", "cardNumber"));
        return new ActionDefinitionView(
                "create-expense-draft", "创建报销草稿", "http-action-v1", "definition-v1",
                endpointRevision, "config-hmac-" + endpointRevision, "policy-v1", schema,
                ActionRiskLevel.LOW_RISK_WRITE, "expense:draft", Set.of("amount", "memo", "cardNumber"),
                true, "创建一条报销草稿", "外部系统将新增一条待提交草稿");
    }
}
