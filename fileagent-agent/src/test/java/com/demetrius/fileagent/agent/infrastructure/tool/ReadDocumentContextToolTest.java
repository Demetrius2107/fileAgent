package com.demetrius.fileagent.agent.infrastructure.tool;

import com.demetrius.fileagent.agent.application.tool.AgentToolContext;
import com.demetrius.fileagent.agent.domain.run.AgentRun;
import com.demetrius.fileagent.api.dto.KnowledgeScope;
import com.demetrius.fileagent.api.port.KnowledgeContextPort;
import com.demetrius.fileagent.common.exception.BizException;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class ReadDocumentContextToolTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

    @Test
    void readDocumentContextShouldRejectChunkNotReturnedByThisRunSearch() {
        AgentRun run = AgentRun.pending("r", 1L, "t");
        run.start(NOW);
        run.addAllowedChunk("chunk-1");
        AgentToolContext context = new AgentToolContext(run, null, null, null, KnowledgeScope.global(), 4000);
        ReadDocumentContextTool tool = new ReadDocumentContextTool();

        assertThatThrownBy(() -> tool.execute(context, List.of("chunk-2")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未在本次检索结果中");
    }

    @Test
    void readDocumentContextShouldRejectTooManyChunks() {
        AgentRun run = AgentRun.pending("r", 1L, "t");
        run.start(NOW);
        AgentToolContext context = new AgentToolContext(run, null, null, null, KnowledgeScope.global(), 4000);
        ReadDocumentContextTool tool = new ReadDocumentContextTool();

        assertThatThrownBy(() -> tool.execute(context, List.of("c1", "c2", "c3", "c4")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("1~3");
    }

    @Test
    void readDocumentContextShouldReadAllowedChunks() {
        KnowledgeContextPort port = mock(KnowledgeContextPort.class);
        when(port.read(List.of("chunk-1"))).thenReturn(List.of(
                new KnowledgeContextPort.KnowledgeChunkContext("chunk-1", 1L, "a.pdf", "片段内容", "目标", "s-1", 0)));
        AgentRun run = AgentRun.pending("r", 1L, "t");
        run.start(NOW);
        run.addAllowedChunk("chunk-1");
        AgentToolContext context = new AgentToolContext(run, null, null, port, KnowledgeScope.global(), 4000);
        ReadDocumentContextTool tool = new ReadDocumentContextTool();

        ToolResultBlock result = tool.execute(context, List.of("chunk-1"));

        String text = ((TextBlock) result.getOutput().getFirst()).getText();
        assertThat(text).contains("chunk-1", "a.pdf", "片段内容");
    }

    @Test
    void readDocumentContextShouldShareTotalResultBudgetAcrossChunks() {
        KnowledgeContextPort port = mock(KnowledgeContextPort.class);
        when(port.read(List.of("chunk-1", "chunk-2"))).thenReturn(List.of(
                new KnowledgeContextPort.KnowledgeChunkContext("chunk-1", 1L, "a.pdf", "甲".repeat(3000), null, null, 0),
                new KnowledgeContextPort.KnowledgeChunkContext("chunk-2", 1L, "a.pdf", "乙".repeat(3000), null, null, 1)));
        AgentRun run = AgentRun.pending("r", 1L, "t");
        run.start(NOW);
        run.addAllowedChunk("chunk-1");
        run.addAllowedChunk("chunk-2");
        AgentToolContext context = new AgentToolContext(run, null, null, port, KnowledgeScope.global(), 4000);

        ToolResultBlock result = new ReadDocumentContextTool().execute(context, List.of("chunk-1", "chunk-2"));

        String text = text(result);
        assertThat(text.codePointCount(0, text.length())).isLessThanOrEqualTo(4000);
        assertThat(run.documentReadCharacters()).isGreaterThan(0);
        verify(port).read(List.of("chunk-1", "chunk-2"));
    }

    @Test
    void readDocumentContextShouldNotCallPortWhenSharedBudgetIsExhausted() {
        KnowledgeContextPort port = mock(KnowledgeContextPort.class);
        AgentRun run = AgentRun.pending("r", 1L, "t");
        run.start(NOW);
        run.addAllowedChunk("chunk-1");
        run.recordToolResultCharacters(12000);
        AgentToolContext context = new AgentToolContext(run, null, null, port, KnowledgeScope.global(), 4000);

        ToolResultBlock result = new ReadDocumentContextTool().execute(context, List.of("chunk-1"));

        assertThat(text(result)).contains("预算已用尽");
        verify(port, never()).read(List.of("chunk-1"));
    }

    private String text(ToolResultBlock block) {
        return ((TextBlock) block.getOutput().getFirst()).getText();
    }
}
