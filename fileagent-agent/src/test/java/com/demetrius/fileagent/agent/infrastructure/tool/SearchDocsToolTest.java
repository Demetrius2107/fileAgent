package com.demetrius.fileagent.agent.infrastructure.tool;

import com.demetrius.fileagent.agent.application.tool.AgentToolContext;
import com.demetrius.fileagent.agent.domain.run.AgentRun;
import com.demetrius.fileagent.api.dto.KnowledgeScope;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import com.demetrius.fileagent.common.exception.BizException;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchDocsToolTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

    private AgentToolContext context(AgentRun run, KnowledgeSearchPort port) {
        return new AgentToolContext(run, port, null, null, KnowledgeScope.global(), 4000);
    }

    private AgentRun startedRun() {
        AgentRun run = AgentRun.pending("r", 1L, "t");
        run.start(NOW);
        return run;
    }

    @Test
    void executeShouldSearchAndAddHitsToWhitelist() {
        KnowledgeSearchPort port = mock(KnowledgeSearchPort.class);
        when(port.search(any(KnowledgeSearchPort.SearchQuery.class))).thenReturn(List.of(
                new KnowledgeSearchPort.KnowledgeHit("c-1", 1L, "正文内容", "a.pdf", null, null, null, 0, 0.9)));
        AgentRun run = startedRun();
        SearchDocsTool tool = new SearchDocsTool();

        ToolResultBlock result = tool.execute(context(run, port), "  关键词  ");

        assertThat(run.isAllowedChunk("c-1")).isTrue();
        String text = ((TextBlock) result.getOutput().getFirst()).getText();
        assertThat(text).contains("c-1", "a.pdf", "正文内容");
    }

    @Test
    void executeShouldRejectBlankQuery() {
        SearchDocsTool tool = new SearchDocsTool();
        AgentRun run = startedRun();

        assertThatThrownBy(() -> tool.execute(context(run, mock(KnowledgeSearchPort.class)), "   "))
                .isInstanceOf(BizException.class);
    }

    @Test
    void executeShouldRejectOverlongQuery() {
        SearchDocsTool tool = new SearchDocsTool();
        AgentRun run = startedRun();

        assertThatThrownBy(() -> tool.execute(context(run, mock(KnowledgeSearchPort.class)), "a".repeat(201)))
                .isInstanceOf(BizException.class);
    }
}
