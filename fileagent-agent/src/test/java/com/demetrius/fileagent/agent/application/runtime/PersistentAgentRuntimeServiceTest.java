package com.demetrius.fileagent.agent.application.runtime;

import com.demetrius.fileagent.agent.application.AgentApprovalService;
import com.demetrius.fileagent.agent.domain.approval.AgentApprovalEntity;
import com.demetrius.fileagent.agent.domain.approval.AgentApprovalRepository;
import com.demetrius.fileagent.agent.domain.run.AgentRunEntity;
import com.demetrius.fileagent.agent.domain.run.AgentRunRepository;
import com.demetrius.fileagent.agent.infrastructure.run.AgentExecutionHandleRegistry;
import com.demetrius.fileagent.api.dto.AgentApprovalView;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import com.demetrius.fileagent.api.dto.AgentRunSnapshot;
import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import com.demetrius.fileagent.api.enums.ApprovalStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 持久化 Run 查询、订阅和显式取消测试。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-10
 */
@ExtendWith(MockitoExtension.class)
class PersistentAgentRuntimeServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"), ZoneOffset.UTC);

    @Mock
    private AgentRunRepository runRepository;
    @Mock
    private AgentApprovalRepository approvalRepository;
    @Mock
    private AgentApprovalService approvalService;
    @Mock
    private AgentRunEventHub eventHub;
    @Mock
    private AgentExecutionHandleRegistry executionHandleRegistry;
    @Mock
    private AgentRunExecutionCoordinator executionCoordinator;

    private PersistentAgentRuntimeService service;

    @BeforeEach
    void setUp() {
        service = new PersistentAgentRuntimeService(runRepository, approvalRepository, approvalService, eventHub,
                executionHandleRegistry, executionCoordinator, CLOCK);
    }

    @Test
    void shouldPersistCancellationBeforeInterruptingExecutionHandle() {
        AgentRunEntity running = running("run-1");
        when(runRepository.findByRunId("run-1")).thenReturn(Optional.of(running));
        when(runRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(eventHub.publish(any(AgentRunEvent.class), eq(0L))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentRunSnapshot snapshot = service.cancel("run-1");

        assertThat(snapshot.status()).isEqualTo(AgentRunStatus.CANCELLED);
        verify(runRepository).save(running);
        verify(eventHub).publish(any(AgentRunEvent.class), eq(0L));
        verify(executionHandleRegistry).interrupt("run-1");
        verify(executionCoordinator).releaseCapacity("run-1");
    }

    @Test
    void shouldSubscribeUsingPersistedSnapshotAndCursor() {
        AgentRunEntity pending = pending("run-2");
        when(runRepository.findByRunId("run-2")).thenReturn(Optional.of(pending));
        when(eventHub.subscribe("run-2", "event-1", AgentRunStatus.PENDING, 0L))
                .thenReturn(Flux.just(AgentRunEvent.pending("run-2")));

        List<AgentRunEvent> events = service.events("run-2", "event-1").collectList().block();

        assertThat(events).singleElement().extracting(AgentRunEvent::type).isEqualTo("run.status");
        verify(eventHub).subscribe("run-2", "event-1", AgentRunStatus.PENDING, 0L);
    }

    @Test
    void shouldLeaveCapacityHeldUntilInterruptedRunningExecutionTerminates() {
        AgentRunEntity running = running("run-3");
        when(runRepository.findByRunId("run-3")).thenReturn(Optional.of(running));
        when(runRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(eventHub.publish(any(AgentRunEvent.class), eq(0L))).thenAnswer(invocation -> invocation.getArgument(0));
        when(executionHandleRegistry.interrupt("run-3")).thenReturn(true);

        service.cancel("run-3");

        verify(executionCoordinator, never()).releaseCapacity("run-3");
    }

    @Test
    void shouldReturnNullWhenSessionHasNoActiveRun() {
        when(runRepository.findActiveBySessionId(1L)).thenReturn(Optional.empty());

        assertThat(service.active(1L)).isNull();
    }

    @Test
    void shouldExposePendingApprovalInWaitingRunSnapshot() {
        AgentRunEntity waiting = running("run-waiting");
        waiting.waitForApproval(CLOCK.instant().plusSeconds(1));
        AgentApprovalEntity approvalEntity = mock(AgentApprovalEntity.class);
        AgentApprovalView approval = new AgentApprovalView("approval-1", "managed_http_action",
                ActionRiskLevel.HIGH_RISK_WRITE, "执行测试动作", "resource:demo", "影响一条演示记录",
                "{\"payload\":\"***\"}", "input-hmac", CLOCK.instant().plusSeconds(300),
                ApprovalStatus.PENDING);
        when(runRepository.findByRunId("run-waiting")).thenReturn(Optional.of(waiting));
        when(approvalRepository.findPendingByRunId("run-waiting")).thenReturn(Optional.of(approvalEntity));
        when(approvalService.toView(approvalEntity)).thenReturn(approval);

        AgentRunSnapshot snapshot = service.snapshot("run-waiting");

        assertThat(snapshot.status()).isEqualTo(AgentRunStatus.WAITING_APPROVAL);
        assertThat(snapshot.approval()).isEqualTo(approval);
    }

    @Test
    void shouldCancelWaitingApprovalBeforeCancellingRun() {
        AgentRunEntity waiting = running("run-cancel-waiting");
        waiting.waitForApproval(CLOCK.instant().plusSeconds(1));
        when(runRepository.findByRunId("run-cancel-waiting")).thenReturn(Optional.of(waiting));
        when(runRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentRunSnapshot snapshot = service.cancel("run-cancel-waiting");

        assertThat(snapshot.status()).isEqualTo(AgentRunStatus.CANCELLED);
        verify(approvalService).cancelPendingForRun("run-cancel-waiting", "RUN_CANCELLED");
    }

    private AgentRunEntity pending(String runId) {
        return AgentRunEntity.pending(runId, 1L, "trace-" + runId,
                "local-operator", "cipher-" + runId, "hmac-" + runId);
    }

    private AgentRunEntity running(String runId) {
        AgentRunEntity run = pending(runId);
        run.start(CLOCK.instant());
        return run;
    }
}
