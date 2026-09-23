package com.demetrius.fileagent.agent.infrastructure.tool;

import com.demetrius.fileagent.agent.application.tool.AgentToolContext;
import com.demetrius.fileagent.agent.domain.run.AgentRun;
import com.demetrius.fileagent.api.dto.KnowledgeScope;
import com.demetrius.fileagent.api.enums.ParseStatus;
import com.demetrius.fileagent.api.port.KnowledgeCatalogPort;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author raosaijie
 */
class ListKnowledgeFilesToolTest {

    @Test
    void listShouldAuthorizeOnlyReturnedFiles() {
        KnowledgeCatalogPort port = mock(KnowledgeCatalogPort.class);
        when(port.list(new KnowledgeCatalogPort.Query(null, null, 20))).thenReturn(List.of(
                new KnowledgeCatalogPort.KnowledgeFile(7L, "rag", "tag", "a.pdf", ParseStatus.SUCCESS, 2),
                new KnowledgeCatalogPort.KnowledgeFile(8L, "rag", "tag", "b.pdf", ParseStatus.SUCCESS, 3)));
        AgentRun run = startedRun();
        AgentToolContext context = new AgentToolContext(run, null, port, null, KnowledgeScope.global(), 4000);

        ToolResultBlock result = new ListKnowledgeFilesTool().execute(context, null, null);

        assertThat(run.allowedFileIds()).containsExactlyInAnyOrder(7L, 8L);
        assertThat(text(result)).contains("fileId=7", "fileId=8");
    }

    @Test
    void listShouldNotCallPortWhenSharedBudgetIsExhausted() {
        KnowledgeCatalogPort port = mock(KnowledgeCatalogPort.class);
        AgentRun run = startedRun();
        run.recordToolResultCharacters(12000);
        AgentToolContext context = new AgentToolContext(run, null, port, null, KnowledgeScope.global(), 4000);

        ToolResultBlock result = new ListKnowledgeFilesTool().execute(context, null, null);

        assertThat(text(result)).contains("预算已用尽");
        verify(port, never()).list(new KnowledgeCatalogPort.Query(null, null, 20));
    }

    private AgentRun startedRun() {
        AgentRun run = AgentRun.pending("r", 1L, "t");
        run.start(Instant.parse("2026-09-16T10:00:00Z"));
        return run;
    }

    private String text(ToolResultBlock block) {
        return ((TextBlock) block.getOutput().getFirst()).getText();
    }
}
