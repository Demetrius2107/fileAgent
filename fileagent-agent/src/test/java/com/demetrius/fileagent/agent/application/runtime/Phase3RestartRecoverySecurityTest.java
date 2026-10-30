package com.demetrius.fileagent.agent.application.runtime;

import com.demetrius.fileagent.agent.application.AgentApprovalService;
import com.demetrius.fileagent.agent.application.AgentRunFinalizationService;
import com.demetrius.fileagent.agent.domain.approval.AgentApprovalEntity;
import com.demetrius.fileagent.agent.domain.approval.AgentApprovalRepository;
import com.demetrius.fileagent.agent.domain.run.AgentRunEntity;
import com.demetrius.fileagent.agent.domain.run.AgentRunRepository;
import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import com.demetrius.fileagent.api.dto.action.PythonSandboxActionDefinition;
import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.enums.ApprovalStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 重启恢复门禁：旧进程已批准但尚未执行的 Python 请求不得在新进程中续跑。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
class Phase3RestartRecoverySecurityTest {

    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void shouldFailClosedApprovedPythonRunAfterRestartWithoutExecutingIt() {
        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        AgentApprovalRepository approvalRepository = mock(AgentApprovalRepository.class);
        AgentApprovalService approvalService = mock(AgentApprovalService.class);
        AgentExecutionEngine executionEngine = mock(AgentExecutionEngine.class);
        AgentRunCommandCodec commandCodec = mock(AgentRunCommandCodec.class);
        AgentProperties properties = new AgentProperties();
        properties.setMaxConcurrentRuns(1);
        properties.setMaxQueuedRuns(1);
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.initialize();

        AgentRunEntity run = AgentRunEntity.pending("phase3-restart-run", 991L, "trace-phase3",
                "local-operator", "ciphertext", "request-hmac");
        AgentApprovalEntity approval = approvedPythonApproval(run.getRunId());
        when(runRepository.findRecoverable(any(Instant.class))).thenReturn(List.of(run));
        when(approvalRepository.findLatestResolvedByRunId(run.getRunId())).thenReturn(Optional.of(approval));
        doAnswer(invocation -> {
            run.fail("PYTHON_RESTART_REJECTED", Instant.now());
            return run;
        }).when(approvalService).failApprovedPythonRecovery(run.getRunId(), approval.getApprovalId());

        AgentRunExecutionCoordinator coordinator = new AgentRunExecutionCoordinator(
                runRepository, approvalRepository, executionEngine, commandCodec,
                new AgentRunEventHub(properties), mock(AgentRunFinalizationService.class), approvalService,
                properties, Clock.systemUTC(), executor);

        coordinator.recoverNow();

        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.getFailureCode()).isEqualTo("PYTHON_RESTART_REJECTED");
        verify(approvalService).failApprovedPythonRecovery(run.getRunId(), approval.getApprovalId());
        verify(commandCodec, never()).decrypt(any());
        verify(executionEngine, never()).execute(any());
        verify(executionEngine, never()).resume(any(), any());
    }

    private static AgentApprovalEntity approvedPythonApproval(String runId) {
        Instant now = Instant.now();
        AgentApprovalEntity approval = AgentApprovalEntity.pending(
                "phase3-approval", runId, 1L, "python-tool-call", PythonSandboxActionDefinition.TOOL_NAME,
                PythonSandboxActionDefinition.TOOL_VERSION, PythonSandboxActionDefinition.DEFINITION_VERSION,
                PythonSandboxActionDefinition.ENDPOINT_REVISION, PythonSandboxActionDefinition.ENDPOINT_CONFIG_HASH,
                PythonSandboxActionDefinition.POLICY_VERSION, ActionRiskLevel.HIGH_RISK_WRITE,
                PythonSandboxActionDefinition.RESOURCE_SCOPE, "input-hmac", "operation-id",
                "执行 Python", "隔离运行", "{\"code\":\"print(1)\",\"input\":{}}",
                now.plusSeconds(60), now);
        approval.decide(ApprovalStatus.APPROVED, "local-operator", null, "USER_APPROVED", now);
        return approval;
    }
}
