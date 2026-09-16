package com.demetrius.fileagent.agent.infrastructure.runtime;

import com.demetrius.fileagent.api.dto.AgentRunEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeEventMapperTest {

    @Test
    void toolCallEventShouldBecomeMetadataOnlySseEvent() {
        AgentScopeEventMapper mapper = new AgentScopeEventMapper();

        AgentRunEvent event = mapper.toolCompleted("run-1", "search_docs", 1, 3, 42L);

        assertThat(event.type()).isEqualTo("tool.completed");
        assertThat(event.resultCount()).isEqualTo(3);
        assertThat(event.content()).isNull();
        assertThat(event.tool()).isEqualTo("search_docs");
        assertThat(event.durationMs()).isEqualTo(42L);
    }

    @Test
    void toolStartedShouldNotCarryContent() {
        AgentScopeEventMapper mapper = new AgentScopeEventMapper();

        AgentRunEvent event = mapper.toolStarted("run-1", "search_docs", 1);

        assertThat(event.type()).isEqualTo("tool.started");
        assertThat(event.tool()).isEqualTo("search_docs");
        assertThat(event.content()).isNull();
    }

    @Test
    void deltaShouldCarryIncrementalContent() {
        AgentScopeEventMapper mapper = new AgentScopeEventMapper();

        AgentRunEvent event = mapper.delta("run-1", "部分答案");

        assertThat(event.type()).isEqualTo("message.delta");
        assertThat(event.content()).isEqualTo("部分答案");
    }
}
