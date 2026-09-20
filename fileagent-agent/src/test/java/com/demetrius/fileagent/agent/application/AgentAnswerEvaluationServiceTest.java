package com.demetrius.fileagent.agent.application;

import com.demetrius.fileagent.agent.infrastructure.runtime.AgentScopeRuntimeAdapter;
import com.demetrius.fileagent.api.dto.AgentRunCommand;
import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.port.AgentAnswerEvaluationPort;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import com.demetrius.fileagent.api.port.SessionMessagePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentAnswerEvaluationServiceTest {

    @Mock
    private AgentScopeRuntimeAdapter runtimeAdapter;

    @Mock
    private SessionMessagePort sessionMessagePort;

    private AgentAnswerEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new AgentAnswerEvaluationService(runtimeAdapter, sessionMessagePort);
    }

    @Test
    void shouldDelegateToRuntimeWithoutPersistingMessages() {
        AgentAnswerEvaluationPort.Result expected = new AgentAnswerEvaluationPort.Result(
                "年假 5 天 [来源：employee-handbook.md]", false,
                List.of(new KnowledgeSearchPort.KnowledgeHit(
                        "chunk-1", 1L, "员工入职第一年有 5 天年假", "employee-handbook.md",
                        null, "section-1", "parent-1", 2, 0.91)),
                List.of("employee-handbook.md"), 1, 2, List.of("search_docs"), 150L,
                AgentRunStatus.SUCCEEDED, null, null);
        when(runtimeAdapter.evaluate(any(AgentRunCommand.class))).thenReturn(expected);

        AgentAnswerEvaluationPort.Result result = service.evaluate(new AgentAnswerEvaluationPort.Query(
                "员工入职第一年有多少天年假？", List.of(), "fileagent-eval-v1", "baseline", null));

        assertThat(result).isSameAs(expected);
        verify(runtimeAdapter).evaluate(any(AgentRunCommand.class));
        verifyNoInteractions(sessionMessagePort);
    }

    @Test
    void shouldBuildCommandWithEvaluationScope() {
        when(runtimeAdapter.evaluate(any(AgentRunCommand.class)))
                .thenReturn(new AgentAnswerEvaluationPort.Result(
                        "", true, List.of(), List.of(), 0, 0, List.of(), 0L,
                        AgentRunStatus.SUCCEEDED, null, null));

        service.evaluate(new AgentAnswerEvaluationPort.Query(
                "问题", List.of(), "rag-eval", "tag-eval", 9L));

        verify(runtimeAdapter).evaluate(org.mockito.ArgumentMatchers.argThat(command ->
                command.sessionId() == null
                        && "agent-eval".equals(command.traceId())
                        && "rag-eval".equals(command.knowledgeScope().ragName())
                        && "tag-eval".equals(command.knowledgeScope().knowledgeTag())));
        verifyNoInteractions(sessionMessagePort);
    }
}
