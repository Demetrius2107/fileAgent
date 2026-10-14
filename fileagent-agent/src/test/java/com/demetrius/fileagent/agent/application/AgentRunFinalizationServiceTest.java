package com.demetrius.fileagent.agent.application;

import com.demetrius.fileagent.agent.domain.run.AgentRunEntity;
import com.demetrius.fileagent.agent.domain.run.AgentRunRepository;
import com.demetrius.fileagent.agent.domain.step.AgentStepEntity;
import com.demetrius.fileagent.agent.domain.step.AgentStepRepository;
import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.enums.MessageType;
import com.demetrius.fileagent.api.port.SessionMessagePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 回答最终化事务测试。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-10
 */
@ExtendWith(MockitoExtension.class)
class AgentRunFinalizationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"), ZoneOffset.UTC);

    @Mock
    private AgentRunRepository runRepository;
    @Mock
    private AgentStepRepository stepRepository;
    @Mock
    private SessionMessagePort sessionMessagePort;
    @Mock
    private AgentFinalizationContributor contributor;

    private AgentRunFinalizationService service;

    @BeforeEach
    void setUp() {
        service = new AgentRunFinalizationService(runRepository, stepRepository, sessionMessagePort,
                List.of(contributor), new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void shouldPersistAssistantFinalizationStepAndSucceededRunTogether() {
        AgentRunEntity running = running("run-1");
        when(runRepository.findByRunId("run-1")).thenReturn(Optional.of(running));
        when(stepRepository.findStepsByRunId("run-1")).thenReturn(List.of());
        when(sessionMessagePort.append(1L, MessageType.ASSISTANT, "回答内容")).thenReturn(101L);
        when(runRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stepRepository.save(any(AgentStepEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentRunEntity finalized = service.finalizeSuccess("run-1", "回答内容", List.of("handbook.md"));

        ArgumentCaptor<AgentStepEntity> step = ArgumentCaptor.forClass(AgentStepEntity.class);
        verify(contributor).contribute(running, "回答内容", List.of("handbook.md"));
        verify(stepRepository).save(step.capture());
        assertThat(step.getValue().getStatus().name()).isEqualTo("SUCCEEDED");
        assertThat(finalized.getStatus()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(finalized.getAssistantMessageId()).isEqualTo(101L);
    }

    @Test
    void shouldLeaveRunUnfinalizedWhenAssistantAppendFails() {
        AgentRunEntity running = running("run-2");
        when(runRepository.findByRunId("run-2")).thenReturn(Optional.of(running));
        when(sessionMessagePort.append(eq(1L), eq(MessageType.ASSISTANT), any()))
                .thenThrow(new IllegalStateException("message store unavailable"));

        assertThatThrownBy(() -> service.finalizeSuccess("run-2", "回答内容", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("message store unavailable");

        verify(stepRepository, never()).save(any());
        verify(runRepository, never()).save(any());
        assertThat(running.getStatus()).isEqualTo(AgentRunStatus.RUNNING);
    }

    private AgentRunEntity running(String runId) {
        AgentRunEntity run = AgentRunEntity.pending(runId, 1L, "trace-" + runId,
                "local-operator", "cipher-" + runId, "hmac-" + runId);
        run.start(CLOCK.instant());
        return run;
    }
}
