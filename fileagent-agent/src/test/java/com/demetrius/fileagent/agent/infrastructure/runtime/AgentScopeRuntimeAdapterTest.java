package com.demetrius.fileagent.agent.infrastructure.runtime;

import com.demetrius.fileagent.agent.application.prompt.AgentPromptFactory;
import com.demetrius.fileagent.agent.application.AgentHistoryService;
import com.demetrius.fileagent.agent.domain.run.AgentRun;
import com.demetrius.fileagent.agent.infrastructure.config.AdaptiveRetrievalProperties;
import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import com.demetrius.fileagent.agent.infrastructure.run.InMemoryAgentRunRegistry;
import com.demetrius.fileagent.agent.infrastructure.tool.ListKnowledgeFilesTool;
import com.demetrius.fileagent.agent.infrastructure.tool.ReadDocumentContextTool;
import com.demetrius.fileagent.agent.infrastructure.tool.SearchDocsTool;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import com.demetrius.fileagent.api.port.KnowledgeCatalogPort;
import com.demetrius.fileagent.api.port.KnowledgeContextPort;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentScopeRuntimeAdapterTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-16T10:00:00Z"), ZoneOffset.UTC);

    private AgentScopeRuntimeAdapter adapter;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        adapter = new AgentScopeRuntimeAdapter(
                properties,
                new AdaptiveRetrievalProperties(),
                new InMemoryAgentRunRegistry(properties, CLOCK),
                mock(AgentScopeModelFactory.class),
                mock(AgentPromptFactory.class),
                mock(KnowledgeSearchPort.class),
                mock(KnowledgeCatalogPort.class),
                mock(KnowledgeContextPort.class),
                new AgentScopeEventMapper(),
                mock(SearchDocsTool.class),
                mock(ListKnowledgeFilesTool.class),
                mock(ReadDocumentContextTool.class),
                mock(AgentHistoryService.class));
    }

    @Test
    void toolFailureShouldInterruptAgentAndEmitOnlyOneFailureEvent() {
        AgentRun run = AgentRun.pending("run-1", 1L, "trace-1");
        run.start(CLOCK.instant());
        ReActAgent agent = mock(ReActAgent.class);
        RuntimeContext context = RuntimeContext.builder()
                .sessionId("1")
                .userId("server")
                .build();
        run.recordToolFailure(AgentRun.KNOWLEDGE_SEARCH_FAILURE_CODE);

        List<AgentRunEvent> events = adapter.handleToolResultEnd(
                run, agent, context, "search_docs", 1, 12L).collectList().block();

        verify(agent).interrupt(context);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("run.failed");
            assertThat(event.code()).isEqualTo(AgentRun.KNOWLEDGE_SEARCH_FAILURE_CODE);
            assertThat(event.message()).isEqualTo("知识库检索暂时不可用");
        });
        assertThat(run.isTerminal()).isTrue();
    }

    @Test
    void shouldExtractSeparateFilenamesFromCombinedCitationWithoutDroppingUnknownSources() {
        assertThat(adapter.extractSources("[来源：a.md；b.md] [来源：a.md] [来源：unknown.md]"))
                .containsExactly("a.md", "b.md", "unknown.md");
    }
}
