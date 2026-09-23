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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author raosaijie
 */
class GetDocumentOutlineToolTest {

    @Test
    void outlineShouldRejectFileOutsideRunAuthorization() {
        AgentRun run = startedRun();
        AgentToolContext context = new AgentToolContext(run, null, null, mock(KnowledgeContextPort.class),
                KnowledgeScope.global(), 4000);

        assertThatThrownBy(() -> new GetDocumentOutlineTool().execute(context, 7L, -1, 50))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未授权");
    }

    @Test
    void outlineShouldAuthorizeReturnedChunkAndParent() {
        KnowledgeContextPort port = mock(KnowledgeContextPort.class);
        when(port.outline(7L, -1, 50)).thenReturn(new KnowledgeContextPort.DocumentOutlinePage(
                List.of(new KnowledgeContextPort.DocumentOutlineItem(
                        "child-1", "parent-1", "a.pdf", "text", null, "s-1", null, 0, "预览")), null));
        AgentRun run = startedRun();
        run.addAllowedFile(7L);
        AgentToolContext context = new AgentToolContext(run, null, null, port, KnowledgeScope.global(), 4000);

        ToolResultBlock result = new GetDocumentOutlineTool().execute(context, 7L, -1, 50);

        assertThat(run.allowedChunkIds()).containsExactlyInAnyOrder("child-1", "parent-1");
        assertThat(text(result)).contains("child-1", "parent-1", "预览");
        verify(port).outline(7L, -1, 50);
    }

    @Test
    void outlineShouldNotCallPortWhenSharedBudgetIsExhausted() {
        KnowledgeContextPort port = mock(KnowledgeContextPort.class);
        AgentRun run = startedRun();
        run.addAllowedFile(7L);
        run.recordToolResultCharacters(12000);
        AgentToolContext context = new AgentToolContext(run, null, null, port, KnowledgeScope.global(), 4000);

        ToolResultBlock result = new GetDocumentOutlineTool().execute(context, 7L, -1, 50);

        assertThat(text(result)).contains("预算已用尽");
        verify(port, never()).outline(7L, -1, 50);
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
