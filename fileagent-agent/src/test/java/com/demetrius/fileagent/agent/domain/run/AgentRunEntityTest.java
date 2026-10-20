package com.demetrius.fileagent.agent.domain.run;

import com.demetrius.fileagent.api.enums.AgentRunStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author raosaijie
 */
class AgentRunEntityTest {

    private static final Instant NOW = Instant.parse("2026-10-09T08:00:00Z");

    @Test
    void shouldRejectTerminalTransition() {
        AgentRunEntity run = AgentRunEntity.pending(
                "run-1", 1L, "trace-1", "local-operator", "cipher", "hmac");
        run.start(NOW);
        run.succeed(10L, NOW.plusSeconds(1));

        assertThatThrownBy(() -> run.fail("AGENT_MODEL_UNAVAILABLE", NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法状态转移");
    }

    @Test
    void shouldAllowPendingFailureAndReleaseActiveSlot() {
        AgentRunEntity run = AgentRunEntity.pending(
                "run-1", 1L, "trace-1", "local-operator", "cipher", "hmac");

        run.fail("AGENT_RUN_INTERRUPTED", NOW);

        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.getFailureCode()).isEqualTo("AGENT_RUN_INTERRUPTED");
        assertThat(run.getActiveSlot()).isNull();
        assertThat(run.getExecutionOwner()).isNull();
        assertThat(run.getLeaseExpiresAt()).isNull();
    }

    @Test
    void shouldKeepSessionSlotWhileWaitingForApprovalAndReturnToPendingForResume() {
        AgentRunEntity run = AgentRunEntity.pending(
                "run-approval", 1L, "trace-1", "local-operator", "cipher", "hmac");
        run.start(NOW);

        run.waitForApproval(NOW.plusSeconds(1));

        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.WAITING_APPROVAL);
        assertThat(run.getActiveSlot()).isEqualTo("session:1");
        assertThat(run.getExecutionOwner()).isNull();
        assertThat(run.getLeaseExpiresAt()).isNull();

        run.resumeAfterApproval(NOW.plusSeconds(2));

        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.PENDING);
        assertThat(run.getActiveSlot()).isEqualTo("session:1");
        assertThat(run.getStartedAt()).isEqualTo(NOW);
    }
}
