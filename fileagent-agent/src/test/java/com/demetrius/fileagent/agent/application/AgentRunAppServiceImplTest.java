package com.demetrius.fileagent.agent.application;

import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import com.demetrius.fileagent.agent.interfaces.dto.StartAgentRunRequest;
import com.demetrius.fileagent.api.dto.AgentRunCommand;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import com.demetrius.fileagent.api.enums.MessageType;
import com.demetrius.fileagent.api.port.AgentRuntimePort;
import com.demetrius.fileagent.api.port.SessionMessagePort;
import com.demetrius.fileagent.api.port.SessionQueryPort;
import com.demetrius.fileagent.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.argThat;

@ExtendWith(MockitoExtension.class)
class AgentRunAppServiceImplTest {

    @Mock
    private SessionQueryPort sessionQueryPort;
    @Mock
    private SessionMessagePort sessionMessagePort;
    @Mock
    private AgentRuntimePort agentRuntimePort;
    @Mock
    private AgentProperties properties;

    @InjectMocks
    private AgentRunAppServiceImpl service;

    @BeforeEach
    void enableAgentByDefault() {
        when(properties.isEnabled()).thenReturn(true);
    }

    @Test
    void shouldPersistUserThenAssistantOnCompletion() {
        when(sessionQueryPort.exists(1L)).thenReturn(true);
        when(sessionQueryPort.listMessages(1L)).thenReturn(List.of());
        when(agentRuntimePort.run(any(AgentRunCommand.class))).thenReturn(Flux.just(
                AgentRunEvent.delta("run-1", "你好"),
                AgentRunEvent.sources("run-1", List.of("a.pdf")),
                AgentRunEvent.completed("run-1", null)));
        when(sessionMessagePort.append(eq(1L), eq(MessageType.USER), eq("问题"))).thenReturn(100L);
        when(sessionMessagePort.append(eq(1L), eq(MessageType.ASSISTANT), eq("你好"))).thenReturn(101L);

        List<AgentRunEvent> events = service.run(1L, new StartAgentRunRequest("问题", null), "trace-1")
                .collectList().block();

        verify(sessionMessagePort).append(1L, MessageType.USER, "问题");
        verify(sessionMessagePort).append(1L, MessageType.ASSISTANT, "你好");
        assertThat(events).extracting(AgentRunEvent::type)
                .containsExactly("message.delta", "sources", "run.completed");
        assertThat(events.get(2).messageId()).isEqualTo(101L);
    }

    @Test
    void shouldNotPersistAssistantWhenRunFailed() {
        when(sessionQueryPort.exists(1L)).thenReturn(true);
        when(sessionQueryPort.listMessages(1L)).thenReturn(List.of());
        when(agentRuntimePort.run(any(AgentRunCommand.class))).thenReturn(Flux.just(
                AgentRunEvent.failed("run-1", "AGENT_RUN_TIMEOUT", "运行超时", "trace-1")));
        when(sessionMessagePort.append(eq(1L), eq(MessageType.USER), eq("问题"))).thenReturn(100L);

        List<AgentRunEvent> events = service.run(1L, new StartAgentRunRequest("问题", null), "trace-1")
                .collectList().block();

        verify(sessionMessagePort).append(1L, MessageType.USER, "问题");
        verify(sessionMessagePort, never()).append(eq(1L), eq(MessageType.ASSISTANT), anyString());
        assertThat(events).extracting(AgentRunEvent::type).containsExactly("run.failed");
    }

    @Test
    void shouldRejectUnknownSession() {
        when(sessionQueryPort.exists(9L)).thenReturn(false);

        assertThatThrownBy(() -> service.run(9L, new StartAgentRunRequest("问题", null), "trace-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("会话不存在");

        verifyNoInteractions(agentRuntimePort, sessionMessagePort);
    }

    @Test
    void shouldRejectBlankPrompt() {
        assertThatThrownBy(() -> service.run(1L, new StartAgentRunRequest("  ", null), "trace-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("prompt");
    }

    @Test
    void shouldReturnFeatureDisabledWhenFlagOff() {
        when(properties.isEnabled()).thenReturn(false);

        List<AgentRunEvent> events = service.run(1L, new StartAgentRunRequest("问题", null), "trace-1")
                .collectList().block();

        assertThat(events).extracting(AgentRunEvent::type).containsExactly("run.failed");
        assertThat(events.get(0).code()).isEqualTo("AGENT_FEATURE_DISABLED");
        verifyNoInteractions(agentRuntimePort, sessionMessagePort, sessionQueryPort);
    }

    @Test
    void adaptiveRunShouldPassPersistedSummaryBaselineToRuntime() {
        when(properties.isAdaptiveRetrievalEnabled()).thenReturn(true);
        when(sessionQueryPort.exists(1L)).thenReturn(true);
        when(sessionQueryPort.getSummary(1L)).thenReturn(new SessionQueryPort.SessionSummary(
                "{\"confirmedFacts\":[\"已确认\"]}", 7L, null, 3L, "hash-3"));
        when(sessionQueryPort.listMessagesAfter(1L, 7L)).thenReturn(List.of());
        when(agentRuntimePort.run(argThat(command ->
                command.historySummary().contains("已确认")
                        && command.historySummaryThroughMessageId().equals(7L)
                        && command.historySummaryVersion() == 3L)))
                .thenReturn(Flux.just(AgentRunEvent.delta("run-1", "回答"),
                        AgentRunEvent.completed("run-1", null)));
        when(sessionMessagePort.append(eq(1L), eq(MessageType.USER), eq("问题"))).thenReturn(100L);
        when(sessionMessagePort.append(eq(1L), eq(MessageType.ASSISTANT), eq("回答"))).thenReturn(101L);

        List<AgentRunEvent> events = service.run(1L,
                        new StartAgentRunRequest("问题", null), "trace-1")
                .collectList().block();

        assertThat(events).extracting(AgentRunEvent::type)
                .containsExactly("message.delta", "run.completed");
        verify(sessionQueryPort).listMessagesAfter(1L, 7L);
    }

    @Test
    void shouldRejectPromptBeforeSavingUserMessageWhenItExceedsBudget() {
        when(properties.getMaxPromptCharacters()).thenReturn(3);
        when(sessionQueryPort.exists(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.run(1L,
                new StartAgentRunRequest("超过限制", null), "trace-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("AGENT_PROMPT_TOO_LARGE");

        verifyNoInteractions(sessionMessagePort, agentRuntimePort);
    }
}
