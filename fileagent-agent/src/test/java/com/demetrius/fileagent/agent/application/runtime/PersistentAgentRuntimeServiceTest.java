package com.demetrius.fileagent.agent.application.runtime;

import com.demetrius.fileagent.agent.domain.run.AgentRunEntity;
import com.demetrius.fileagent.agent.domain.run.AgentRunRepository;
import com.demetrius.fileagent.agent.infrastructure.run.AgentExecutionHandleRegistry;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import com.demetrius.fileagent.api.dto.AgentRunSnapshot;
import com.demetrius.fileagent.api.enums.AgentRunStatus;
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
    private AgentRunEventHub eventHub;
    @Mock
    private AgentExecutionHandleRegistry executionHandleRegistry;
    @Mock
    private AgentRunExecutionCoordinator executionCoordinator;

    private PersistentAgentRuntimeService service;

    @BeforeEach
    void setUp() {
        service = new PersistentAgentRuntimeService(runRepository, eventHub, executionHandleRegistry,
                executionCoordinator, CLOCK);
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
