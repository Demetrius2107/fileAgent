package com.demetrius.fileagent.agent.infrastructure.tool;

import com.demetrius.fileagent.agent.domain.policy.AgentActionCandidate;
import com.demetrius.fileagent.agent.domain.policy.AgentActionPolicy;
import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行级副作用工具的权限与执行绑定验证。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-12
 */
class RunScopedActionToolTest {

    @Test
    void shouldValidateThenAskBeforeAnyRiskyActionExecution() {
        RecordingActionTool tool = new RecordingActionTool();

        assertThat(tool.checkPermissions(input(), PermissionContextState.builder().build()).block().getBehavior())
                .isEqualTo(PermissionBehavior.ASK);
        assertThat(tool.calls()).containsExactly("schema", "candidate");
    }

    @Test
    void shouldExecuteOnlyAfterAgentScopePromotesAskCallToAllowed() {
        RecordingActionTool tool = new RecordingActionTool();
        tool.checkPermissions(input(), PermissionContextState.builder().build()).block();

        StepVerifier.create(tool.callAsync(param(ToolCallState.ALLOWED)))
                .assertNext(result -> assertThat(result.getOutput()).isNotEmpty())
                .verifyComplete();

        assertThat(tool.calls()).containsExactly("schema", "candidate", "schema", "candidate", "execute");
    }

    @Test
    void shouldRejectRiskyActionWhenAskCallHasNotBeenConfirmed() {
        RecordingActionTool tool = new RecordingActionTool();
        tool.checkPermissions(input(), PermissionContextState.builder().build()).block();

        StepVerifier.create(tool.callAsync(param(ToolCallState.PENDING)))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("未获得用户批准"))
                .verify();
        assertThat(tool.calls()).containsExactly("schema", "candidate", "schema", "candidate");
    }

    @Test
    void shouldRejectActionWhenTrustedBindingChangesAfterPermissionCheck() {
        RecordingActionTool tool = new RecordingActionTool();
        tool.checkPermissions(input(), PermissionContextState.builder().build()).block();
        tool.setEndpointRevision("endpoint-v2");

        StepVerifier.create(tool.callAsync(param(ToolCallState.ALLOWED)))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("动作绑定已变化"))
                .verify();
        assertThat(tool.calls()).containsExactly("schema", "candidate", "schema", "candidate");
    }

    private static Map<String, Object> input() {
        return Map.of("payload", "demo");
    }

    private static ToolCallParam param(ToolCallState state) {
        ToolUseBlock toolCall = new ToolUseBlock(
                "tool-call-1", "managed_http_action", input(), null, Map.of(), state);
        return ToolCallParam.builder()
                .toolUseBlock(toolCall)
                .input(input())
                .runtimeContext(RuntimeContext.builder().sessionId("run-1").userId("local-operator").build())
                .build();
    }

    private static final class RecordingActionTool extends RunScopedActionTool {

        private final List<String> calls = new ArrayList<>();
        private String endpointRevision = "endpoint-v1";

        private RecordingActionTool() {
            super("run-1", ToolBase.builder()
                            .name("managed_http_action")
                            .description("测试受控动作")
                            .inputSchema(Map.of("type", "object"))
                            .readOnly(false)
                            .concurrencySafe(false),
                    new AgentActionPolicy(Set.of("managed_http_action")));
        }

        @Override
        protected void validateSchema(Map<String, Object> input) {
            calls.add("schema");
            if (!"demo".equals(input.get("payload"))) {
                throw new IllegalArgumentException("payload 不合法");
            }
        }

        @Override
        protected AgentActionCandidate buildCandidate(Map<String, Object> input, String toolCallId) {
            calls.add("candidate");
            return new AgentActionCandidate(
                    "run-1", toolCallId, "managed_http_action", "tool-v1", "schema-v1",
                    endpointRevision, "endpoint-hash", "policy-v1", ActionRiskLevel.HIGH_RISK_WRITE,
                    "resource:demo", Set.of("resource:demo"), input, false,
                    "{\"payload\":\"demo\"}", "input-hmac", "operation-1",
                    "执行测试动作", "影响一条演示记录", "{\"payload\":\"***\"}");
        }

        @Override
        protected ToolResultBlock executeVerified(AgentActionCandidate candidate) {
            calls.add("execute");
            return ToolResultBlock.text("执行完成");
        }

        private List<String> calls() {
            return List.copyOf(calls);
        }

        private void setEndpointRevision(String endpointRevision) {
            this.endpointRevision = endpointRevision;
        }
    }
}
