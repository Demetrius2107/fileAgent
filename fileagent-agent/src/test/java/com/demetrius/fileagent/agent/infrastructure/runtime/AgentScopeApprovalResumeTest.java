package com.demetrius.fileagent.agent.infrastructure.runtime;

import com.demetrius.fileagent.agent.application.AgentApprovalService;
import com.demetrius.fileagent.agent.application.AgentHistoryService;
import com.demetrius.fileagent.agent.application.prompt.AgentPromptFactory;
import com.demetrius.fileagent.agent.domain.approval.AgentApprovalEntity;
import com.demetrius.fileagent.agent.domain.policy.AgentActionCandidate;
import com.demetrius.fileagent.agent.domain.policy.AgentActionPolicy;
import com.demetrius.fileagent.agent.domain.service.AdaptiveRetrievalPolicy;
import com.demetrius.fileagent.agent.infrastructure.config.AdaptiveRetrievalProperties;
import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import com.demetrius.fileagent.agent.infrastructure.run.AgentExecutionHandleRegistry;
import com.demetrius.fileagent.agent.infrastructure.state.DatabaseAgentStateStore;
import com.demetrius.fileagent.agent.infrastructure.state.StrictAgentStateLoader;
import com.demetrius.fileagent.agent.infrastructure.tool.AgentActionToolFactory;
import com.demetrius.fileagent.agent.infrastructure.tool.GetDocumentOutlineTool;
import com.demetrius.fileagent.agent.infrastructure.tool.ListKnowledgeFilesTool;
import com.demetrius.fileagent.agent.infrastructure.tool.ReadDocumentContextTool;
import com.demetrius.fileagent.agent.infrastructure.tool.RunScopedActionTool;
import com.demetrius.fileagent.agent.infrastructure.tool.SearchDocsTool;
import com.demetrius.fileagent.api.dto.AgentApprovalView;
import com.demetrius.fileagent.api.dto.AgentRunCommand;
import com.demetrius.fileagent.api.dto.KnowledgeScope;
import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import com.demetrius.fileagent.api.enums.ApprovalStatus;
import com.demetrius.fileagent.api.port.KnowledgeCatalogPort;
import com.demetrius.fileagent.api.port.KnowledgeContextPort;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolBase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentScope 原生 ConfirmResult 恢复验证：浏览器不重传参数，运行时只使用检查点中的 ASKING ToolCall。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-12
 */
class AgentScopeApprovalResumeTest {

    @Test
    void shouldResumeApprovedAskingToolCallUsingStoredCheckpoint() {
        String runId = "run-resume";
        ToolUseBlock storedToolCall = new ToolUseBlock("tool-call-1", "managed_http_action",
                Map.of("payload", "demo"), "{\"payload\":\"stale\"}", Map.of(), ToolCallState.ASKING);
        AgentState state = AgentState.builder()
                .sessionId(runId)
                .userId("local-operator")
                .context(List.of(Msg.builder().role(MsgRole.ASSISTANT)
                        .name("fileagent-knowledge-agent")
                        .content(storedToolCall)
                        .build()))
                .build();
        DatabaseAgentStateStore stateStore = mock(DatabaseAgentStateStore.class);
        when(stateStore.get(anyString(), eq(runId), eq("agent_state"), eq(AgentState.class)))
                .thenReturn(Optional.of(state));
        StrictAgentStateLoader strictStateLoader = mock(StrictAgentStateLoader.class);
        when(strictStateLoader.loadVerified(runId)).thenReturn(state);
        AtomicInteger executionCount = new AtomicInteger();
        List<String> actionStages = new CopyOnWriteArrayList<>();
        AtomicReference<String> observedToolResult = new AtomicReference<>();
        AgentProperties properties = new AgentProperties();
        properties.setActionsEnabled(true);
        AgentScopeModelFactory modelFactory = mock(AgentScopeModelFactory.class);
        when(modelFactory.create()).thenReturn(new CompletedModel(observedToolResult));
        AgentApprovalService approvalService = mock(AgentApprovalService.class);

        AgentScopeRuntimeAdapter adapter = new AgentScopeRuntimeAdapter(
                properties,
                new AdaptiveRetrievalProperties(),
                new AgentExecutionHandleRegistry(),
                stateStore,
                strictStateLoader,
                modelFactory,
                new AgentPromptFactory(),
                mock(KnowledgeSearchPort.class),
                mock(KnowledgeCatalogPort.class),
                mock(KnowledgeContextPort.class),
                new AgentScopeEventMapper(),
                new SearchDocsTool(properties, mock(AdaptiveRetrievalPolicy.class)),
                new ListKnowledgeFilesTool(),
                new ReadDocumentContextTool(),
                new GetDocumentOutlineTool(),
                mock(AgentHistoryService.class),
                approvalService,
                List.of((AgentActionToolFactory) command -> new ResumedActionTool(
                        command.runId(), executionCount, actionStages)),
                new com.demetrius.fileagent.agent.application.AgentCitationService(
                        mock(KnowledgeContextPort.class)));

        List<String> eventTypes = adapter.resume(new AgentRunCommand(
                        runId, 1L, "trace-resume", "执行测试操作", List.of(), KnowledgeScope.global()),
                approved(runId))
                .map(event -> event.type())
                .collectList()
                .block();

        assertThat(eventTypes).contains("run.started", "tool.started", "tool.completed", "run.completed")
                .doesNotContain("run.failed");
        assertThat(observedToolResult).hasValue("执行完成");
        assertThat(actionStages).containsExactly("schema", "candidate", "schema", "candidate", "execute");
        assertThat(executionCount).hasValue(1);
        verify(approvalService).completeApprovedStep(runId, "approval-1", "tool-call-1");
    }

    @Test
    void shouldRebuildApprovalFromVerifiedAskingCheckpointAfterInterruptedRun() {
        String runId = "run-rebuild";
        ToolUseBlock storedToolCall = new ToolUseBlock("tool-call-1", "managed_http_action",
                Map.of("payload", "demo"), null, Map.of(), ToolCallState.ASKING);
        AgentState state = AgentState.builder()
                .sessionId(runId)
                .userId("local-operator")
                .context(List.of(Msg.builder().role(MsgRole.ASSISTANT)
                        .name("fileagent-knowledge-agent")
                        .content(storedToolCall)
                        .build()))
                .build();
        DatabaseAgentStateStore stateStore = mock(DatabaseAgentStateStore.class);
        StrictAgentStateLoader strictStateLoader = mock(StrictAgentStateLoader.class);
        when(strictStateLoader.loadVerified(runId)).thenReturn(state);
        AgentApprovalService approvalService = mock(AgentApprovalService.class);
        AgentApprovalView approval = new AgentApprovalView("approval-1", "managed_http_action",
                ActionRiskLevel.HIGH_RISK_WRITE, "执行测试动作", "resource:demo", "影响一条演示记录",
                "{\"payload\":\"***\"}", "input-hmac", Instant.parse("2026-10-12T08:05:00Z"),
                ApprovalStatus.PENDING);
        when(approvalService.pause(eq(runId), any(AgentActionCandidate.class))).thenReturn(approval);
        AgentProperties properties = new AgentProperties();
        properties.setActionsEnabled(true);

        AgentScopeRuntimeAdapter adapter = new AgentScopeRuntimeAdapter(
                properties,
                new AdaptiveRetrievalProperties(),
                new AgentExecutionHandleRegistry(),
                stateStore,
                strictStateLoader,
                mock(AgentScopeModelFactory.class),
                new AgentPromptFactory(),
                mock(KnowledgeSearchPort.class),
                mock(KnowledgeCatalogPort.class),
                mock(KnowledgeContextPort.class),
                new AgentScopeEventMapper(),
                new SearchDocsTool(properties, mock(AdaptiveRetrievalPolicy.class)),
                new ListKnowledgeFilesTool(),
                new ReadDocumentContextTool(),
                new GetDocumentOutlineTool(),
                mock(AgentHistoryService.class),
                approvalService,
                List.of((AgentActionToolFactory) command -> new ResumedActionTool(
                        command.runId(), new AtomicInteger(), new CopyOnWriteArrayList<>())),
                new com.demetrius.fileagent.agent.application.AgentCitationService(
                        mock(KnowledgeContextPort.class)));

        assertThat(adapter.recoverWaitingApproval(new AgentRunCommand(
                runId, 1L, "trace-rebuild", "执行测试操作", List.of(), KnowledgeScope.global())))
                .contains(approval);

        ArgumentCaptor<AgentActionCandidate> candidateCaptor = ArgumentCaptor.forClass(AgentActionCandidate.class);
        verify(approvalService).pause(eq(runId), candidateCaptor.capture());
        assertThat(candidateCaptor.getValue().toolCallId()).isEqualTo("tool-call-1");
        assertThat(candidateCaptor.getValue().canonicalArguments()).isEqualTo("{\"payload\":\"demo\"}");
    }

    private static AgentApprovalEntity approved(String runId) {
        AgentApprovalEntity approval = AgentApprovalEntity.pending(
                "approval-1", runId, 9L, "tool-call-1", "managed_http_action", "tool-v1", "schema-v1",
                "endpoint-v1", "endpoint-hash", "policy-v1", ActionRiskLevel.HIGH_RISK_WRITE,
                "resource:demo", "input-hmac", "operation-1", "执行测试动作", "影响一条演示记录",
                "{\"payload\":\"***\"}", Instant.parse("2026-10-12T08:05:00Z"),
                Instant.parse("2026-10-12T08:00:00Z"));
        approval.decide(ApprovalStatus.APPROVED, "local-operator", null, "USER_APPROVED",
                Instant.parse("2026-10-12T08:01:00Z"));
        return approval;
    }

    private static final class CompletedModel implements Model {

        private final AtomicReference<String> observedToolResult;

        private CompletedModel(AtomicReference<String> observedToolResult) {
            this.observedToolResult = observedToolResult;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            messages.stream()
                    .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                    .flatMap(result -> result.getOutput().stream())
                    .filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast)
                    .map(TextBlock::getText)
                    .findFirst()
                    .ifPresent(observedToolResult::set);
            return Flux.just(ChatResponse.builder()
                    .id("response-resume")
                    .content(List.of(TextBlock.builder().text("已完成").build()))
                    .finishReason("stop")
                    .build());
        }

        @Override
        public String getModelName() {
            return "completed-model";
        }
    }

    private static final class ResumedActionTool extends RunScopedActionTool {

        private final AtomicInteger executionCount;
        private final List<String> actionStages;

        private ResumedActionTool(String runId, AtomicInteger executionCount, List<String> actionStages) {
            super(runId, ToolBase.builder()
                            .name("managed_http_action")
                            .description("测试审批动作")
                            .inputSchema(Map.of("type", "object"))
                            .readOnly(false)
                            .concurrencySafe(false),
                    new AgentActionPolicy(Set.of("managed_http_action")));
            this.executionCount = executionCount;
            this.actionStages = actionStages;
        }

        @Override
        protected void validateSchema(Map<String, Object> input) {
            actionStages.add("schema");
            if (!"demo".equals(input.get("payload"))) {
                throw new IllegalArgumentException("payload 不合法");
            }
        }

        @Override
        protected AgentActionCandidate buildCandidate(Map<String, Object> input, String toolCallId) {
            actionStages.add("candidate");
            return new AgentActionCandidate(
                    runId(), toolCallId, "managed_http_action", "tool-v1", "schema-v1",
                    "endpoint-v1", "endpoint-hash", "policy-v1", ActionRiskLevel.HIGH_RISK_WRITE,
                    "resource:demo", Set.of("resource:demo"), input, false,
                    "{\"payload\":\"demo\"}", "input-hmac", "operation-1",
                    "执行测试动作", "影响一条演示记录", "{\"payload\":\"***\"}");
        }

        @Override
        protected ToolResultBlock executeVerified(AgentActionCandidate candidate) {
            actionStages.add("execute");
            executionCount.incrementAndGet();
            return ToolResultBlock.text("执行完成");
        }
    }
}
