package com.demetrius.fileagent.agent.infrastructure.runtime;

import com.demetrius.fileagent.agent.application.prompt.AgentPromptFactory;
import com.demetrius.fileagent.agent.application.AgentHistoryService;
import com.demetrius.fileagent.agent.domain.run.AgentRun;
import com.demetrius.fileagent.agent.infrastructure.config.AdaptiveRetrievalProperties;
import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import com.demetrius.fileagent.agent.infrastructure.run.InMemoryAgentRunRegistry;
import com.demetrius.fileagent.agent.infrastructure.tool.ListKnowledgeFilesTool;
import com.demetrius.fileagent.agent.infrastructure.tool.GetDocumentOutlineTool;
import com.demetrius.fileagent.agent.infrastructure.tool.ReadDocumentContextTool;
import com.demetrius.fileagent.agent.infrastructure.tool.SearchDocsTool;
import com.demetrius.fileagent.api.dto.AgentRunSnapshot;
import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.port.KnowledgeCatalogPort;
import com.demetrius.fileagent.api.port.KnowledgeContextPort;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import com.demetrius.fileagent.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentRuntimeCancellationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-16T10:00:00Z"), ZoneOffset.UTC);

    private InMemoryAgentRunRegistry registry;
    private AgentScopeRuntimeAdapter adapter;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        registry = new InMemoryAgentRunRegistry(properties, CLOCK);
        adapter = new AgentScopeRuntimeAdapter(
                properties,
                new AdaptiveRetrievalProperties(),
                registry,
                mock(AgentScopeModelFactory.class),
                mock(AgentPromptFactory.class),
                mock(KnowledgeSearchPort.class),
                mock(KnowledgeCatalogPort.class),
                mock(KnowledgeContextPort.class),
                new AgentScopeEventMapper(),
                mock(SearchDocsTool.class),
                mock(ListKnowledgeFilesTool.class),
                mock(ReadDocumentContextTool.class),
                mock(GetDocumentOutlineTool.class),
                mock(AgentHistoryService.class));
    }

    @Test
    void cancelShouldInterruptActiveRuntimeAndEndAsCancelled() {
        AgentRun run = AgentRun.pending("run-1", 1L, "trace-1");
        run.start(CLOCK.instant());
        registry.save(run);
        Runnable interruptor = mock(Runnable.class);
        registry.registerCancelHandle("run-1", interruptor);

        AgentRunSnapshot snapshot = adapter.cancel("run-1");

        verify(interruptor).run();
        assertThat(snapshot.status()).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(registry.find("run-1").orElseThrow().status()).isEqualTo(AgentRunStatus.CANCELLED);
    }

    @Test
    void cancelShouldBeIdempotentForTerminalRun() {
        AgentRun run = AgentRun.pending("run-1", 1L, "trace-1");
        run.start(CLOCK.instant());
        run.succeed(CLOCK.instant());
        registry.save(run);

        AgentRunSnapshot snapshot = adapter.cancel("run-1");

        assertThat(snapshot.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
    }

    @Test
    void snapshotShouldThrowNotFoundForUnknownRun() {
        assertThatThrownBy(() -> adapter.snapshot("missing"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
    }
}
