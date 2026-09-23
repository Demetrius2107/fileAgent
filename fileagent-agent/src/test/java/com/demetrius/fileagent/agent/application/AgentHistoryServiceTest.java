package com.demetrius.fileagent.agent.application;

import com.demetrius.fileagent.agent.application.port.HistorySummaryPort;
import com.demetrius.fileagent.agent.domain.run.AgentRun;
import com.demetrius.fileagent.agent.domain.run.AgentRunBudget;
import com.demetrius.fileagent.api.dto.AgentRunCommand;
import com.demetrius.fileagent.api.dto.KnowledgeScope;
import com.demetrius.fileagent.api.dto.MessageDto;
import com.demetrius.fileagent.api.enums.MessageType;
import com.demetrius.fileagent.api.port.SessionMessagePort;
import com.demetrius.fileagent.api.port.SessionQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 历史上下文选择与滚动摘要服务测试。
 *
 * @author raosaijie
 */
@ExtendWith(MockitoExtension.class)
class AgentHistoryServiceTest {

    @Mock
    private HistorySummaryPort historySummaryPort;
    @Mock
    private SessionQueryPort sessionQueryPort;
    @Mock
    private SessionMessagePort sessionMessagePort;

    private AgentHistoryService service;

    @BeforeEach
    void setUp() {
        service = new AgentHistoryService(historySummaryPort, sessionQueryPort, sessionMessagePort);
    }

    @Test
    void shouldKeepCompleteRecentMessagesWithoutCallingSummaryModelWhenWithinBudget() {
        AgentRun run = runningRun();
        AgentRunBudget budget = budget(100, 200);
        AgentRunCommand command = command(null, List.of(message(1L, "早期"), message(2L, "最近")));

        AgentHistoryService.PreparedHistory result = service.prepare(run, budget, command);

        assertThat(result.summary()).isNull();
        assertThat(result.recentMessages()).extracting(MessageDto::id).containsExactly(1L, 2L);
        assertThat(result.compressed()).isFalse();
        verify(historySummaryPort, never()).summarize(any(), any());
    }

    @Test
    void shouldSummarizeOldMessagesAndKeepNewestMessagesComplete() {
        AgentRun run = runningRun();
        AgentRunBudget budget = budget(10, 20);
        when(historySummaryPort.summarize(any(), any()))
                .thenReturn(new HistorySummaryPort.SummaryResult(
                        "{\"confirmedFacts\":[\"早期约束\"]}", 30, 10, 40));
        AgentRunCommand command = command(null, List.of(
                message(1L, "早期约束"), message(2L, "这是较长的最近消息")));

        AgentHistoryService.PreparedHistory result = service.prepare(run, budget, command);

        assertThat(result.summary()).contains("早期约束");
        assertThat(result.recentMessages()).extracting(MessageDto::id).containsExactly(2L);
        assertThat(result.compressed()).isTrue();
        assertThat(run.modelCallCount()).isEqualTo(1);
        assertThat(run.totalTokens()).isEqualTo(40);
        verify(historySummaryPort).summarize(eq(null), any());
    }

    @Test
    void shouldFallbackToExistingSummaryWhenSummaryModelFails() {
        AgentRun run = runningRun();
        AgentRunBudget budget = budget(4, 40);
        when(historySummaryPort.summarize(any(), any())).thenThrow(new IllegalStateException("模型失败"));
        AgentRunCommand command = command("{\"confirmedFacts\":[\"已确认\"]}", List.of(
                message(1L, "旧消息"), message(2L, "最近消息")));

        AgentHistoryService.PreparedHistory result = service.prepare(run, budget, command);

        assertThat(result.summary()).contains("已确认");
        assertThat(run.budgetReasons()).contains("SUMMARY_FAILED_FALLBACK");
        assertThat(result.recentMessages()).extracting(MessageDto::id).containsExactly(2L);
    }

    private AgentRun runningRun() {
        AgentRun run = AgentRun.pending("run-history", 1L, "trace-history");
        run.start(java.time.Instant.parse("2026-09-23T00:00:00Z"));
        return run;
    }

    private AgentRunBudget budget(int recentCharacters, int totalHistoryCharacters) {
        return new AgentRunBudget(8, 6, 4000, 12000, 8000,
                totalHistoryCharacters, 2000, recentCharacters, 500, 50, 3,
                60000, java.time.Duration.ofSeconds(90));
    }

    private AgentRunCommand command(String summary, List<MessageDto> history) {
        return new AgentRunCommand("run-history", 1L, "trace-history", "当前问题",
                summary, null, 0L, null, history, KnowledgeScope.global());
    }

    private MessageDto message(Long id, String content) {
        return new MessageDto(id, 1L, MessageType.USER, content, null, "2026-09-23T00:00:00");
    }
}
