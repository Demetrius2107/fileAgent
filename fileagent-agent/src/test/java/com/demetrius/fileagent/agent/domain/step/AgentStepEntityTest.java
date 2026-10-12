package com.demetrius.fileagent.agent.domain.step;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author raosaijie
 */
class AgentStepEntityTest {

    private static final Instant NOW = Instant.parse("2026-10-09T08:00:00Z");

    @Test
    void shouldRejectTransitionAfterStepSucceeded() {
        AgentStepEntity step = AgentStepEntity.pending(
                "run-1", 1, AgentStepType.MODEL_CALL, NOW);
        step.start(NOW.plusSeconds(1));
        step.succeed("MODEL_COMPLETED", "已完成模型调用", NOW.plusSeconds(2));

        assertThat(step.getStatus()).isEqualTo(AgentStepStatus.SUCCEEDED);
        assertThatThrownBy(() -> step.fail("AGENT_MODEL_UNAVAILABLE", NOW.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法步骤状态转移");
    }

    @Test
    void shouldResumeWaitingApprovalStepWithoutCreatingAnotherStep() {
        AgentStepEntity step = AgentStepEntity.pending(
                "run-approval", 1, AgentStepType.TOOL_CALL, NOW);
        step.start(NOW.plusSeconds(1));
        step.waitForApproval();

        step.resumeAfterApproval();

        assertThat(step.getStatus()).isEqualTo(AgentStepStatus.RUNNING);
        assertThat(step.getStartedAt()).isEqualTo(NOW.plusSeconds(1));
    }
}
