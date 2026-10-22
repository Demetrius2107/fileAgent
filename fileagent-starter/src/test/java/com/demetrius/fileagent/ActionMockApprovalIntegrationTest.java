package com.demetrius.fileagent;

import com.demetrius.fileagent.action.infrastructure.persistence.MockExpenseDraftJpaRepository;
import com.demetrius.fileagent.action.infrastructure.persistence.ActionToolExecutionJpaRepository;
import com.demetrius.fileagent.action.application.MockExpenseDraftService;
import com.demetrius.fileagent.agent.application.AgentApprovalWorkflowService;
import com.demetrius.fileagent.agent.application.AgentRunAppService;
import com.demetrius.fileagent.agent.domain.run.AgentRunRepository;
import com.demetrius.fileagent.agent.infrastructure.runtime.AgentScopeModelFactory;
import com.demetrius.fileagent.agent.interfaces.dto.StartAgentRunRequest;
import com.demetrius.fileagent.api.dto.AgentApprovalDecision;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import com.demetrius.fileagent.api.dto.CreateSessionReq;
import com.demetrius.fileagent.api.dto.action.ActionExecutionCommand;
import com.demetrius.fileagent.api.enums.ActionExecutionStatus;
import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.port.ActionExecutionPort;
import com.demetrius.fileagent.document.infrastructure.ElasticsearchKnowledgeIndexInitializer;
import com.demetrius.fileagent.session.application.SessionAppService;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * 启动真实应用和内置 Mock，验证审批到本机 HTTP 的完整调用链。
 *
 * @author raosaijie
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("action-mock")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ActionMockApprovalIntegrationTest {

    private static final int PORT = freePort();
    private static String completedRunId;
    private static String completedApprovalId;
    private static String waitingRunId;
    private static String waitingApprovalId;

    @MockitoBean
    private ElasticsearchKnowledgeIndexInitializer indexInitializer;

    @MockitoBean
    private AgentScopeModelFactory modelFactory;

    @Autowired
    private SessionAppService sessionService;

    @Autowired
    private AgentRunAppService runService;

    @Autowired
    private AgentApprovalWorkflowService approvalService;

    @Autowired
    private AgentRunRepository runRepository;

    @Autowired
    private MockExpenseDraftJpaRepository draftRepository;

    @Autowired
    private ActionToolExecutionJpaRepository executionRepository;

    @Autowired
    private ActionExecutionPort actionExecutionPort;

    @MockitoSpyBean
    private MockExpenseDraftService mockService;

    private final AtomicReference<String> observedCatalog = new AtomicReference<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("FILEAGENT_ACTION_MOCK_BASE_URL", () -> "http://127.0.0.1:" + PORT);
        registry.add("FILEAGENT_ACTION_MOCK_TOKEN", () -> "integration-test-token");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:action_mock_integration;DB_CLOSE_DELAY=-1");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("fileagent.secret-key-path", () -> "target/action-mock-integration.key");
    }

    @BeforeEach
    void setUp() {
        observedCatalog.set(null);
        when(modelFactory.create()).thenReturn(new DeterministicModel(observedCatalog));
    }

    @Test
    @Order(1)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void shouldOnlyCallMockHttpAfterApprovalAndNotRepeatOnDuplicateDecision() {
        draftRepository.deleteAll();
        AgentRunEvent denied = startAndWaitForApproval("deny");
        assertThat(draftRepository.count()).isZero();
        approvalService.decide(denied.runId(), denied.approval().approvalId(),
                new AgentApprovalDecision(AgentApprovalDecision.Decision.DENY, null));
        awaitTerminal(denied.runId());
        assertThat(draftRepository.count()).isZero();

        AgentRunEvent approved = startAndWaitForApproval("approve");
        assertThat(draftRepository.count()).isZero();
        var decision = new AgentApprovalDecision(AgentApprovalDecision.Decision.APPROVE, null);
        approvalService.decide(approved.runId(), approved.approval().approvalId(), decision);
        assertThat(awaitTerminal(approved.runId())).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(draftRepository.count()).isEqualTo(1);
        String draftId = draftRepository.findAll().getFirst().getDraftId();

        approvalService.decide(approved.runId(), approved.approval().approvalId(), decision);
        assertThat(draftRepository.count()).isEqualTo(1);
        assertThat(draftRepository.findAll().getFirst().getDraftId()).isEqualTo(draftId);

        var execution = executionRepository.findAll().stream()
                .filter(value -> approved.runId().equals(value.getRunId())).findFirst().orElseThrow();
        var definition = actionExecutionPort.resolveDefinition("mock-create-expense-draft");
        var replay = actionExecutionPort.execute(new ActionExecutionCommand(
                approved.runId(), execution.getToolCallId(), execution.getToolName(), execution.getToolVersion(),
                definition.actionId(), definition.definitionVersion(), definition.endpointRevision(),
                definition.endpointConfigHash(), definition.policyVersion(), execution.getExecutionKey(),
                execution.getOperationFingerprint(), execution.getOperationId(),
                "{\"amount\":100,\"bankAccount\":\"account-secret\",\"memo\":\"integration test\"}",
                execution.getCanonicalRequestHmac(), "local-operator", Set.of("mock:expense:draft"),
                "mock:expense:draft"));
        assertThat(replay.status()).isEqualTo(ActionExecutionStatus.SUCCEEDED);
        assertThat(replay.resultSummary()).contains(draftId);
        verify(mockService, times(1)).create(anyString(), any(), anyString(), anyString());
        assertThat(observedCatalog.get()).contains("mock-create-expense-draft", "mock-get-expense-draft")
                .doesNotContain("integration-test-token", "127.0.0.1", "sensitive", "x-location");
        completedRunId = approved.runId();
        completedApprovalId = approved.approval().approvalId();

        AgentRunEvent waiting = startAndWaitForApproval("restart-waiting");
        waitingRunId = waiting.runId();
        waitingApprovalId = waiting.approval().approvalId();
        assertThat(draftRepository.count()).isEqualTo(1);
    }

    @Test
    @Order(2)
    void shouldResumeWaitingApprovalAfterApplicationContextRestartWithoutRepeatingCompletedAction() {
        assertThat(waitingRunId).isNotBlank();
        assertThat(runRepository.findByRunId(waitingRunId).orElseThrow().getStatus())
                .isEqualTo(AgentRunStatus.WAITING_APPROVAL);
        assertThat(draftRepository.count()).isEqualTo(1);
        var approve = new AgentApprovalDecision(AgentApprovalDecision.Decision.APPROVE, null);

        approvalService.decide(completedRunId, completedApprovalId, approve);
        verify(mockService, never()).create(anyString(), any(), anyString(), anyString());
        assertThat(draftRepository.count()).isEqualTo(1);

        approvalService.decide(waitingRunId, waitingApprovalId, approve);
        assertThat(awaitTerminal(waitingRunId)).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(draftRepository.count()).isEqualTo(2);
        approvalService.decide(waitingRunId, waitingApprovalId, approve);
        assertThat(draftRepository.count()).isEqualTo(2);
        verify(mockService, times(1)).create(anyString(), any(), anyString(), anyString());
    }

    private AgentRunEvent startAndWaitForApproval(String title) {
        Long sessionId = sessionService.createSession(new CreateSessionReq(title)).id();
        var preparation = runService.prepare(sessionId, new StartAgentRunRequest("创建测试报销草稿", null), "test-trace");
        AgentRunEvent event = preparation.events().filter(value -> "approval.required".equals(value.type()))
                .next().block(Duration.ofSeconds(20));
        assertThat(event).isNotNull();
        assertThat(event.approval().parameterPreview()).contains("[REDACTED]").doesNotContain("account-secret");
        return event;
    }

    private AgentRunStatus awaitTerminal(String runId) {
        return Flux.interval(Duration.ofMillis(50))
                .map(ignored -> runRepository.findByRunId(runId).orElseThrow().getStatus())
                .filter(status -> status == AgentRunStatus.SUCCEEDED || status == AgentRunStatus.FAILED
                        || status == AgentRunStatus.CANCELLED || status == AgentRunStatus.TIMED_OUT)
                .blockFirst(Duration.ofSeconds(20));
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("无法为集成测试选择端口", e);
        }
    }

    private record DeterministicModel(AtomicReference<String> catalog) implements Model {
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            tools.stream().filter(tool -> "call_registered_http_api".equals(tool.getName()))
                    .findFirst().ifPresent(tool -> catalog.set(tool.getDescription()));
            boolean alreadyCalled = messages.stream()
                    .flatMap(message -> message.getContentBlocks(ToolUseBlock.class).stream())
                    .anyMatch(tool -> "call_registered_http_api".equals(tool.getName()));
            if (alreadyCalled) {
                return Flux.just(ChatResponse.builder().id("finished")
                        .content(List.of(TextBlock.builder().text("操作已处理").build())).finishReason("stop").build());
            }
            return Flux.just(ChatResponse.builder().id("request-action")
                    .content(List.of(new ToolUseBlock("call-1", "call_registered_http_api",
                            Map.of("endpointId", "mock-create-expense-draft", "arguments",
                                    Map.of("amount", 100, "memo", "integration test", "bankAccount", "account-secret")),
                            null, Map.of(), ToolCallState.PENDING)))
                    .finishReason("tool_calls").build());
        }

        @Override
        public String getModelName() {
            return "deterministic-action-test";
        }
    }
}
