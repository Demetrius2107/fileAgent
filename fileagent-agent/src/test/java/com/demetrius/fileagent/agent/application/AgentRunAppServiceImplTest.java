package com.demetrius.fileagent.agent.application;

import com.demetrius.fileagent.agent.application.runtime.AgentRunCommandCodec;
import com.demetrius.fileagent.agent.application.runtime.AgentRunEventHub;
import com.demetrius.fileagent.agent.application.runtime.AgentRunExecutionCoordinator;
import com.demetrius.fileagent.agent.domain.run.AgentRunEntity;
import com.demetrius.fileagent.agent.domain.run.AgentRunRepository;
import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import com.demetrius.fileagent.agent.interfaces.dto.StartAgentRunRequest;
import com.demetrius.fileagent.api.dto.AgentRunCommand;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.enums.MessageType;
import com.demetrius.fileagent.api.port.SessionMessagePort;
import com.demetrius.fileagent.api.port.SessionQueryPort;
import com.demetrius.fileagent.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Agent Run 同步准备编排测试。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-10
 */
@ExtendWith(MockitoExtension.class)
class AgentRunAppServiceImplTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"), ZoneOffset.UTC);

    @Mock
    private SessionQueryPort sessionQueryPort;
    @Mock
    private SessionMessagePort sessionMessagePort;
    @Mock
    private AgentRunRepository runRepository;
    @Mock
    private AgentRunCommandCodec commandCodec;
    @Mock
    private AgentRunExecutionCoordinator executionCoordinator;
    @Mock
    private AgentRunEventHub eventHub;
    @Mock
    private AgentProperties properties;
    @Mock
    private Clock clock;

    @InjectMocks
    private AgentRunAppServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(properties.isEnabled()).thenReturn(true);
        lenient().when(clock.instant()).thenReturn(CLOCK.instant());
        lenient().when(executionCoordinator.tryReserveCapacity(anyString())).thenReturn(true);
        lenient().when(commandCodec.encrypt(any(AgentRunCommand.class)))
                .thenReturn(new AgentRunCommandCodec.EncodedCommand("cipher", "hmac"));
        lenient().when(runRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(eventHub.subscribe(anyString(), isNull(), eq(AgentRunStatus.PENDING), anyLong()))
                .thenAnswer(invocation -> Flux.just(AgentRunEvent.pending(invocation.getArgument(0))));
        ReflectionTestUtils.setField(service, "historyLimit", 10);
    }

    @Test
    void shouldPersistUserAndPendingRunBeforePreparationReturns() {
        when(sessionQueryPort.exists(1L)).thenReturn(true);
        when(sessionQueryPort.listMessages(1L)).thenReturn(List.of());
        when(sessionMessagePort.append(1L, MessageType.USER, "问题")).thenReturn(100L);

        AgentRunPreparation preparation = service.prepare(1L, new StartAgentRunRequest("问题", null), "trace-1");

        ArgumentCaptor<AgentRunEntity> run = ArgumentCaptor.forClass(AgentRunEntity.class);
        ArgumentCaptor<AgentRunCommand> command = ArgumentCaptor.forClass(AgentRunCommand.class);
        verify(sessionMessagePort).append(1L, MessageType.USER, "问题");
        verify(runRepository).save(run.capture());
        verify(executionCoordinator).enqueue(command.capture());
        assertThat(run.getValue().getStatus()).isEqualTo(AgentRunStatus.PENDING);
        assertThat(run.getValue().getInputMessageId()).isEqualTo(100L);
        assertThat(command.getValue().runId()).isEqualTo(preparation.runId());
        assertThat(preparation.events().collectList().block())
                .extracting(AgentRunEvent::type)
                .containsExactly("run.status");
    }

    @Test
    void shouldNotWriteUserOrRunWhenCapacityIsExhausted() {
        when(sessionQueryPort.exists(1L)).thenReturn(true);
        when(executionCoordinator.tryReserveCapacity(anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.prepare(1L, new StartAgentRunRequest("问题", null), "trace-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("AGENT_CAPACITY_EXCEEDED");

        verifyNoInteractions(sessionMessagePort, runRepository, commandCodec, eventHub);
        verify(executionCoordinator, never()).enqueue(any());
    }

    @Test
    void shouldRejectUnknownSessionBeforeReservingCapacity() {
        when(sessionQueryPort.exists(9L)).thenReturn(false);

        assertThatThrownBy(() -> service.prepare(9L, new StartAgentRunRequest("问题", null), "trace-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("会话不存在");

        verifyNoInteractions(sessionMessagePort, runRepository, commandCodec, eventHub, executionCoordinator);
    }

    @Test
    void shouldRejectBlankPrompt() {
        assertThatThrownBy(() -> service.prepare(1L, new StartAgentRunRequest("  ", null), "trace-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("prompt");
    }

    @Test
    void shouldRejectFeatureDisabledBeforeAnyPersistence() {
        when(properties.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.prepare(1L, new StartAgentRunRequest("问题", null), "trace-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("AGENT_FEATURE_DISABLED");

        verifyNoInteractions(sessionQueryPort, sessionMessagePort, runRepository, commandCodec, eventHub,
                executionCoordinator);
    }

    @Test
    void adaptiveRunShouldPersistSummaryBaselineInCommand() {
        when(properties.isAdaptiveRetrievalEnabled()).thenReturn(true);
        when(sessionQueryPort.exists(1L)).thenReturn(true);
        when(sessionQueryPort.getSummary(1L)).thenReturn(new SessionQueryPort.SessionSummary(
                "{\"confirmedFacts\":[\"已确认\"]}", 7L, null, 3L, "hash-3"));
        when(sessionQueryPort.listMessagesAfter(1L, 7L)).thenReturn(List.of());
        when(sessionMessagePort.append(eq(1L), eq(MessageType.USER), eq("问题"))).thenReturn(100L);

        service.prepare(1L, new StartAgentRunRequest("问题", null), "trace-1");

        verify(commandCodec).encrypt(org.mockito.ArgumentMatchers.argThat(command ->
                command.historySummary().contains("已确认")
                        && command.historySummaryThroughMessageId().equals(7L)
                        && command.historySummaryVersion() == 3L));
        verify(sessionQueryPort).listMessagesAfter(1L, 7L);
    }

    @Test
    void shouldRejectPromptBeforeSavingUserMessageWhenItExceedsBudget() {
        when(properties.getMaxPromptCharacters()).thenReturn(3);
        when(sessionQueryPort.exists(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.prepare(1L,
                new StartAgentRunRequest("超过限制", null), "trace-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("AGENT_PROMPT_TOO_LARGE");

        verifyNoInteractions(sessionMessagePort, runRepository, commandCodec, eventHub, executionCoordinator);
    }
}
