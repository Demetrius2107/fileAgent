package com.demetrius.fileagent.agent.infrastructure.runtime;

import com.demetrius.fileagent.agent.application.AgentApprovalService;
import com.demetrius.fileagent.agent.application.AgentHistoryService;
import com.demetrius.fileagent.agent.application.prompt.AgentPromptFactory;
import com.demetrius.fileagent.agent.domain.approval.AgentApprovalEntity;
import com.demetrius.fileagent.agent.domain.approval.AgentApprovalRepository;
import com.demetrius.fileagent.agent.domain.checkpoint.AgentCheckpointEntity;
import com.demetrius.fileagent.agent.domain.checkpoint.AgentCheckpointRepository;
import com.demetrius.fileagent.agent.domain.policy.AgentActionCandidate;
import com.demetrius.fileagent.agent.domain.policy.AgentActionPolicy;
import com.demetrius.fileagent.agent.domain.run.AgentRunEntity;
import com.demetrius.fileagent.agent.domain.run.AgentRunRepository;
import com.demetrius.fileagent.agent.domain.service.AdaptiveRetrievalPolicy;
import com.demetrius.fileagent.agent.domain.step.AgentStepEntity;
import com.demetrius.fileagent.agent.domain.step.AgentStepRepository;
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
import com.demetrius.fileagent.api.dto.AgentRunCommand;
import com.demetrius.fileagent.api.dto.KnowledgeScope;
import com.demetrius.fileagent.api.dto.MessageDto;
import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import com.demetrius.fileagent.api.port.KnowledgeCatalogPort;
import com.demetrius.fileagent.api.port.KnowledgeContextPort;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import com.demetrius.fileagent.common.security.AesGcmCipher;
import com.demetrius.fileagent.common.security.EnvelopeCrypto;
import com.demetrius.fileagent.common.security.LocalMasterKeyProvider;
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
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AgentScope ASK 事件到 FileAgent 审批事实的暂停时序验证。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-12
 */
class AgentScopeApprovalPauseTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPersistVerifiedAskingCheckpointBeforePublishingApprovalRequired() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.setActionsEnabled(true);
        Map<String, AgentCheckpointEntity> checkpoints = new ConcurrentHashMap<>();
        AgentCheckpointRepository checkpointRepository = mock(AgentCheckpointRepository.class);
        when(checkpointRepository.findCheckpointByRunId(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(checkpoints.get(invocation.getArgument(0))));
        when(checkpointRepository.save(any(AgentCheckpointEntity.class))).thenAnswer(invocation -> {
            AgentCheckpointEntity checkpoint = invocation.getArgument(0);
            checkpoints.put(checkpoint.getRunId(), checkpoint);
            return checkpoint;
        });
        EnvelopeCrypto crypto = crypto();
        DatabaseAgentStateStore stateStore = new DatabaseAgentStateStore(checkpointRepository, crypto);
        StrictAgentStateLoader strictStateLoader = new StrictAgentStateLoader(stateStore);

        AgentRunEntity persistedRun = AgentRunEntity.pending(
                "run-ask", 1L, "trace-ask", "local-operator", "cipher", "hmac");
        persistedRun.start(Instant.now());
        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        when(runRepository.findByRunId("run-ask")).thenReturn(Optional.of(persistedRun));
        when(runRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AtomicReference<AgentStepEntity> persistedStep = new AtomicReference<>();
        AgentStepRepository stepRepository = mock(AgentStepRepository.class);
        when(stepRepository.findStepsByRunId("run-ask")).thenReturn(List.of());
        when(stepRepository.save(any(AgentStepEntity.class))).thenAnswer(invocation -> {
            AgentStepEntity step = invocation.getArgument(0);
            assignId(step, 9L);
            persistedStep.set(step);
            return step;
        });

        AtomicReference<AgentApprovalEntity> persistedApproval = new AtomicReference<>();
        AgentApprovalRepository approvalRepository = mock(AgentApprovalRepository.class);
        when(approvalRepository.findByRunIdAndToolCallId("run-ask", "tool-call-1"))
                .thenReturn(Optional.empty());
        when(approvalRepository.save(any(AgentApprovalEntity.class))).thenAnswer(invocation -> {
            AgentApprovalEntity approval = invocation.getArgument(0);
            persistedApproval.set(approval);
            return approval;
        });
        AgentApprovalService approvalService = new AgentApprovalService(
                runRepository, stepRepository, approvalRepository, strictStateLoader, crypto, properties,
                Clock.systemUTC());

        AgentScopeModelFactory modelFactory = mock(AgentScopeModelFactory.class);
        when(modelFactory.create()).thenReturn(new AskingModel());
        AgentPromptFactory promptFactory = new AgentPromptFactory();
        AgentHistoryService historyService = mock(AgentHistoryService.class);
        when(historyService.prepare(any(), any(), any())).thenReturn(
                new AgentHistoryService.PreparedHistory(null, List.<MessageDto>of(), false));

        AgentScopeRuntimeAdapter adapter = new AgentScopeRuntimeAdapter(
                properties,
                new AdaptiveRetrievalProperties(),
                new AgentExecutionHandleRegistry(),
                stateStore,
                strictStateLoader,
                modelFactory,
                promptFactory,
                mock(KnowledgeSearchPort.class),
                mock(KnowledgeCatalogPort.class),
                mock(KnowledgeContextPort.class),
                new AgentScopeEventMapper(),
                new SearchDocsTool(properties, mock(AdaptiveRetrievalPolicy.class)),
                new ListKnowledgeFilesTool(),
                new ReadDocumentContextTool(),
                new GetDocumentOutlineTool(),
                historyService,
                approvalService,
                List.of((AgentActionToolFactory) command -> new AskingActionTool(command.runId())));

        List<String> eventTypes = adapter.execute(new AgentRunCommand(
                        "run-ask", 1L, "trace-ask", "执行测试操作", List.of(), KnowledgeScope.global()))
                .map(event -> event.type())
                .collectList()
                .block();

        assertThat(eventTypes).contains("run.started", "tool.started", "approval.required")
                .doesNotContain("run.failed");
        assertThat(checkpoints).containsKey("run-ask");
        List<ToolUseBlock> checkpointToolCalls = strictStateLoader.loadVerified("run-ask").getContext().stream()
                .flatMap(message -> message.getContent().stream())
                .filter(ToolUseBlock.class::isInstance)
                .map(ToolUseBlock.class::cast)
                .toList();
        assertThat(checkpointToolCalls).anySatisfy(toolCall -> {
                    assertThat(toolCall.getId()).isEqualTo("tool-call-1");
                    assertThat(toolCall.getState()).isEqualTo(ToolCallState.ASKING);
                });
        assertThat(persistedRun.getStatus().name()).isEqualTo("WAITING_APPROVAL");
        assertThat(persistedStep.get()).isNotNull();
        assertThat(persistedStep.get().getStatus().name()).isEqualTo("WAITING_APPROVAL");
        assertThat(persistedApproval.get()).isNotNull();
        assertThat(persistedApproval.get().getStatus().name()).isEqualTo("PENDING");
    }

    private EnvelopeCrypto crypto() {
        LocalMasterKeyProvider provider = new LocalMasterKeyProvider(tempDir.resolve("master.key").toString());
        return new EnvelopeCrypto(new AesGcmCipher(provider), provider);
    }

    private static void assignId(AgentStepEntity step, Long id) throws Exception {
        Field field = AgentStepEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(step, id);
    }

    private static final class AskingModel implements Model {

        @Override
        public Flux<ChatResponse> stream(List<io.agentscope.core.message.Msg> messages,
                                         List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(ChatResponse.builder()
                    .id("response-ask")
                    .content(List.of(new ToolUseBlock("tool-call-1", "managed_http_action",
                            Map.of("payload", "demo"), null, Map.of(), ToolCallState.PENDING)))
                    .finishReason("tool_calls")
                    .build());
        }

        @Override
        public String getModelName() {
            return "asking-model";
        }
    }

    private static final class AskingActionTool extends RunScopedActionTool {

        private AskingActionTool(String runId) {
            super(runId, ToolBase.builder()
                            .name("managed_http_action")
                            .description("测试审批动作")
                            .inputSchema(Map.of("type", "object"))
                            .readOnly(false)
                            .concurrencySafe(false),
                    new AgentActionPolicy(Set.of("managed_http_action")));
        }

        @Override
        protected void validateSchema(Map<String, Object> input) {
            if (!"demo".equals(input.get("payload"))) {
                throw new IllegalArgumentException("payload 不合法");
            }
        }

        @Override
        protected AgentActionCandidate buildCandidate(Map<String, Object> input, String toolCallId) {
            return new AgentActionCandidate(
                    runId(), toolCallId, "managed_http_action", "tool-v1", "schema-v1",
                    "endpoint-v1", "endpoint-hash", "policy-v1", ActionRiskLevel.HIGH_RISK_WRITE,
                    "resource:demo", Set.of("resource:demo"), input, false,
                    "{\"payload\":\"demo\"}", "input-hmac", "operation-1",
                    "执行受控动作", "影响一条演示记录", "{\"payload\":\"***\"}");
        }

        @Override
        protected ToolResultBlock executeVerified(AgentActionCandidate candidate) {
            return ToolResultBlock.text("执行完成");
        }
    }
}
