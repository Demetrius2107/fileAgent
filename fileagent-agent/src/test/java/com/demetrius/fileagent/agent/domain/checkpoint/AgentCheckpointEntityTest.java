package com.demetrius.fileagent.agent.domain.checkpoint;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author raosaijie
 */
class AgentCheckpointEntityTest {

    private static final Instant NOW = Instant.parse("2026-10-09T08:00:00Z");

    @Test
    void shouldReplaceOnlyTheLatestStableCheckpointForRun() {
        AgentCheckpointEntity checkpoint = AgentCheckpointEntity.stable(
                "run-1", 1L, "agent-cipher-1", "authorization-cipher-1", "finalization-cipher-1",
                1, 1, "checksum-1", NOW);

        checkpoint.replace(
                2L, "agent-cipher-2", "authorization-cipher-2", "finalization-cipher-2",
                1, 1, "checksum-2", NOW.plusSeconds(1));

        assertThat(checkpoint.getRunId()).isEqualTo("run-1");
        assertThat(checkpoint.getCheckpointSequence()).isEqualTo(2L);
        assertThat(checkpoint.getAgentscopeStateCiphertext()).isEqualTo("agent-cipher-2");
        assertThat(checkpoint.getChecksum()).isEqualTo("checksum-2");
        assertThat(checkpoint.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
    }
}
