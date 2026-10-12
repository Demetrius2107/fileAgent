package com.demetrius.fileagent.agent.application.runtime;

import com.demetrius.fileagent.agent.application.AgentRunFinalizationService;
import com.demetrius.fileagent.agent.domain.approval.AgentApprovalEntity;
import com.demetrius.fileagent.agent.domain.approval.AgentApprovalRepository;
import com.demetrius.fileagent.agent.domain.run.AgentRunEntity;
import com.demetrius.fileagent.agent.domain.run.AgentRunRepository;
import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import com.demetrius.fileagent.api.dto.AgentRunCommand;
import com.demetrius.fileagent.api.dto.AgentApprovalView;
import com.demetrius.fileagent.api.dto.KnowledgeScope;
import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import com.demetrius.fileagent.api.enums.ApprovalStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台协调器的容量预留、恢复收敛和终态隔离测试。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
class AgentRunExecutionCoordinatorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"), ZoneOffset.UTC);

    private final List<ThreadPoolTaskExecutor> executors = new ArrayList<>();

    @AfterEach
    void shutDownExecutors() {
        executors.forEach(ThreadPoolTaskExecutor::shutdown);
    }

    @Test
    void shouldRejectThirtySeventhReservationAndReleasePermitAfterPreparationFailure() {
        AgentRunExecutionCoordinator coordinator = coordinator(mock(AgentRunRepository.class),
                mock(AgentExecutionEngine.class), mock(AgentRunCommandCodec.class), 4, 32);

        for (int index = 0; index < 36; index++) {
            assertThat(coordinator.tryReserveCapacity("run-" + index)).isTrue();
        }
        assertThat(coordinator.tryReserveCapacity("run-36")).isFalse();

        coordinator.releaseCapacity("run-0");

        assertThat(coordinator.tryReserveCapacity("run-36")).isTrue();
    }

    @Test
    void shouldRequeuePendingRunDuringRecovery() throws Exception {
        AgentRunRepository repository = mock(AgentRunRepository.class);
        AgentRunCommandCodec commandCodec = mock(AgentRunCommandCodec.class);
        CountDownLatch executed = new CountDownLatch(1);
        AtomicReference<AgentRunCommand> executedCommand = new AtomicReference<>();
        AgentExecutionEngine engine = command -> Flux.defer(() -> {
            executedCommand.set(command);
            executed.countDown();
            return Flux.never();
        });
        AgentRunExecutionCoordinator coordinator = coordinator(repository, engine, commandCodec, 1, 1);
        AgentRunEntity pending = pending("run-pending", 1L);
        AgentRunCommand command = command("run-pending", 1L);

        when(repository.findRecoverable(any(Instant.class))).thenReturn(List.of(pending));
        when(commandCodec.decrypt(pending)).thenReturn(command);
        when(repository.findByRunId("run-pending")).thenReturn(Optional.of(pending));
        when(repository.acquireLease(anyString(), anyLong(), anyString(), any(Instant.class), any(Instant.class)))
                .thenReturn(true);
        when(repository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        coordinator.recoverNow();

        assertThat(executed.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(pending.getStatus()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(executedCommand).hasValue(command);
    }

    @Test
    void shouldSafelyFailStaleRunningRunWithoutExecutingIt() {
        AgentRunRepository repository = mock(AgentRunRepository.class);
        AgentExecutionEngine engine = mock(AgentExecutionEngine.class);
        AgentRunExecutionCoordinator coordinator = coordinator(repository, engine,
                mock(AgentRunCommandCodec.class), 1, 1);
        AgentRunEntity staleRunning = pending("run-stale", 2L);
        staleRunning.start(CLOCK.instant().minusSeconds(60));
        when(repository.findRecoverable(any(Instant.class))).thenReturn(List.of(staleRunning));
        when(repository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        coordinator.recoverNow();

        assertThat(staleRunning.getStatus()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(staleRunning.getFailureCode()).isEqualTo("AGENT_RUN_INTERRUPTED");
        assertThat(staleRunning.getActiveSlot()).isNull();
        verify(engine, never()).execute(any());
    }

    @Test
    void shouldRebuildWaitingApprovalFromVerifiedCheckpointInsteadOfFailingStaleRun() {
        AgentRunRepository repository = mock(AgentRunRepository.class);
        AgentRunCommandCodec commandCodec = mock(AgentRunCommandCodec.class);
        AgentRunEntity staleRunning = pending("run-asking", 2L);
        staleRunning.start(CLOCK.instant().minusSeconds(60));
        AgentExecutionEngine engine = new AgentExecutionEngine() {
            @Override
            public Flux<com.demetrius.fileagent.api.dto.AgentRunEvent> execute(AgentRunCommand command) {
                return Flux.error(new AssertionError("ASKING 检查点不能作为新请求重新执行"));
            }

            @Override
            public Optional<AgentApprovalView> recoverWaitingApproval(AgentRunCommand command) {
                staleRunning.waitForApproval(CLOCK.instant());
                return Optional.of(new AgentApprovalView("approval-asking", "managed_http_action",
                        ActionRiskLevel.HIGH_RISK_WRITE, "执行测试动作", "resource:demo", "影响一条演示记录",
                        "{\"payload\":\"***\"}", "input-hmac", CLOCK.instant().plusSeconds(300),
                        ApprovalStatus.PENDING));
            }
        };
        AgentRunExecutionCoordinator coordinator = coordinator(repository, engine, commandCodec, 1, 1);
        when(repository.findRecoverable(any(Instant.class))).thenReturn(List.of(staleRunning));
        when(repository.findByRunId(staleRunning.getRunId())).thenReturn(Optional.of(staleRunning));
        when(commandCodec.decrypt(staleRunning)).thenReturn(command(staleRunning.getRunId(), 2L));
        when(repository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        coordinator.recoverNow();

        assertThat(staleRunning.getStatus()).isEqualTo(AgentRunStatus.WAITING_APPROVAL);
        assertThat(staleRunning.getFailureCode()).isNull();
    }

    @Test
    void shouldNotExecuteTerminalRun() throws Exception {
        AgentRunRepository repository = mock(AgentRunRepository.class);
        AgentExecutionEngine engine = mock(AgentExecutionEngine.class);
        AgentRunExecutionCoordinator coordinator = coordinator(repository, engine,
                mock(AgentRunCommandCodec.class), 1, 1);
        AgentRunEntity completed = pending("run-completed", 3L);
        completed.start(CLOCK.instant());
        completed.succeed(30L, CLOCK.instant());
        when(repository.findByRunId("run-completed")).thenReturn(Optional.of(completed));

        assertThat(coordinator.tryReserveCapacity("run-completed")).isTrue();
        coordinator.enqueue(command("run-completed", 3L));
        executor().getThreadPoolExecutor().submit(() -> {
        }).get(1, TimeUnit.SECONDS);

        verify(engine, never()).execute(any());
    }

    @Test
    void shouldRecoverBeforeAcceptingNewCapacityReservationAtStartup() throws Exception {
        AgentRunRepository repository = mock(AgentRunRepository.class);
        AgentRunCommandCodec commandCodec = mock(AgentRunCommandCodec.class);
        CountDownLatch executed = new CountDownLatch(1);
        AgentExecutionEngine engine = command -> Flux.defer(() -> {
            executed.countDown();
            return Flux.never();
        });
        AgentRunExecutionCoordinator coordinator = coordinator(repository, engine, commandCodec, 1, 0);
        AgentRunEntity pending = pending("run-recovery", 4L);
        when(repository.findRecoverable(any(Instant.class))).thenReturn(List.of(pending));
        when(commandCodec.decrypt(pending)).thenReturn(command("run-recovery", 4L));
        when(repository.findByRunId("run-recovery")).thenReturn(Optional.of(pending));
        when(repository.acquireLease(anyString(), anyLong(), anyString(), any(Instant.class), any(Instant.class)))
                .thenReturn(true);
        when(repository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        coordinator.initialize();

        assertThat(executed.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(coordinator.tryReserveCapacity("new-run")).isFalse();
    }

    @Test
    void shouldResumeResolvedApprovalInsteadOfStartingFreshExecution() throws Exception {
        AgentRunRepository repository = mock(AgentRunRepository.class);
        AgentApprovalRepository approvalRepository = mock(AgentApprovalRepository.class);
        AgentRunCommandCodec commandCodec = mock(AgentRunCommandCodec.class);
        CountDownLatch resumed = new CountDownLatch(1);
        AgentExecutionEngine engine = new AgentExecutionEngine() {
            @Override
            public Flux<com.demetrius.fileagent.api.dto.AgentRunEvent> execute(AgentRunCommand command) {
                return Flux.error(new AssertionError("已决审批不能从 execute 重新开始"));
            }

            @Override
            public Flux<com.demetrius.fileagent.api.dto.AgentRunEvent> resume(
                    AgentRunCommand command, AgentApprovalEntity approval) {
                resumed.countDown();
                return Flux.never();
            }
        };
        AgentRunExecutionCoordinator coordinator = coordinator(repository, approvalRepository, engine,
                commandCodec, 1, 1);
        AgentRunEntity pending = pending("run-resume", 5L);
        AgentApprovalEntity approval = approvedApproval(pending.getRunId());
        when(repository.findByRunId(pending.getRunId())).thenReturn(Optional.of(pending));
        when(repository.acquireLease(anyString(), anyLong(), anyString(), any(Instant.class), any(Instant.class)))
                .thenReturn(true);
        when(repository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(approvalRepository.findLatestResolvedByRunId(pending.getRunId())).thenReturn(Optional.of(approval));

        assertThat(coordinator.tryReserveCapacity(pending.getRunId())).isTrue();
        coordinator.enqueue(command(pending.getRunId(), 5L));

        assertThat(resumed.await(1, TimeUnit.SECONDS)).isTrue();
    }

    private AgentRunExecutionCoordinator coordinator(AgentRunRepository repository, AgentExecutionEngine engine,
                                                       AgentRunCommandCodec commandCodec,
                                                       int maxConcurrentRuns, int maxQueuedRuns) {
        return coordinator(repository, mock(AgentApprovalRepository.class), engine, commandCodec,
                maxConcurrentRuns, maxQueuedRuns);
    }

    private AgentRunExecutionCoordinator coordinator(AgentRunRepository repository,
                                                       AgentApprovalRepository approvalRepository,
                                                       AgentExecutionEngine engine,
                                                       AgentRunCommandCodec commandCodec,
                                                       int maxConcurrentRuns, int maxQueuedRuns) {
        AgentProperties properties = new AgentProperties();
        properties.setMaxConcurrentRuns(maxConcurrentRuns);
        properties.setMaxQueuedRuns(maxQueuedRuns);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(maxConcurrentRuns);
        executor.setMaxPoolSize(maxConcurrentRuns);
        executor.setQueueCapacity(maxQueuedRuns);
        executor.initialize();
        executors.add(executor);
        return new AgentRunExecutionCoordinator(repository, approvalRepository, engine, commandCodec,
                new AgentRunEventHub(properties), mock(AgentRunFinalizationService.class), properties, CLOCK, executor);
    }

    private ThreadPoolTaskExecutor executor() {
        return executors.getLast();
    }

    private AgentRunEntity pending(String runId, Long sessionId) {
        return AgentRunEntity.pending(runId, sessionId, "trace-" + runId,
                "local-operator", "cipher-" + runId, "hmac-" + runId);
    }

    private AgentRunCommand command(String runId, Long sessionId) {
        return new AgentRunCommand(runId, sessionId, "trace-" + runId,
                "问题", List.of(), KnowledgeScope.global());
    }

    private AgentApprovalEntity approvedApproval(String runId) {
        AgentApprovalEntity approval = AgentApprovalEntity.pending(
                "approval-" + runId, runId, 9L, "tool-call-1", "managed_http_action", "tool-v1",
                "schema-v1", "endpoint-v1", "endpoint-hash", "policy-v1", ActionRiskLevel.HIGH_RISK_WRITE,
                "resource:demo", "input-hmac", "operation-1", "执行测试动作", "影响一条演示记录",
                "{\"payload\":\"***\"}", CLOCK.instant().plusSeconds(300), CLOCK.instant());
        approval.decide(ApprovalStatus.APPROVED, "local-operator", null, "USER_APPROVED", CLOCK.instant());
        return approval;
    }
}
