package com.demetrius.fileagent.agent.domain.run;

import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.enums.RetrievalQueryType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRunTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

    @Test
    void shouldAllowOnlyLegalStatusTransitions() {
        AgentRun run = AgentRun.pending("run-1", 8L, "trace-1");

        run.start(NOW);
        run.succeed(NOW.plusSeconds(1));

        assertThat(run.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThatThrownBy(() -> run.cancel(NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldRejectIllegalTransitionFromPending() {
        AgentRun run = AgentRun.pending("run-1", 8L, "trace-1");

        assertThatThrownBy(() -> run.succeed(NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldTrackAllowedChunksAndCounts() {
        AgentRun run = AgentRun.pending("run-1", 8L, "trace-1");
        run.start(NOW);

        run.addAllowedChunk("c-1");
        run.incrementStep();
        run.incrementStep();
        run.incrementModelCall();

        assertThat(run.isAllowedChunk("c-1")).isTrue();
        assertThat(run.isAllowedChunk("c-2")).isFalse();
        assertThat(run.stepCount()).isEqualTo(2);
        assertThat(run.modelCallCount()).isEqualTo(1);
    }

    @Test
    void shouldTrackContextUsageAndStopExpansionAtBudget() {
        AgentRun run = AgentRun.pending("run-usage", 8L, "trace-usage");
        run.start(NOW);

        run.addAllowedFile(12L);
        run.recordHistoryUsage(7_500, 2_000, true);
        run.recordSearchSnippetCharacters(500);
        run.recordDocumentReadCharacters(300);
        run.recordToolResultCharacters(4_000);
        run.recordModelUsage(1_000, 2_000, 3_000);
        run.addBudgetReason("HISTORY_SUMMARIZED");

        assertThat(run.isAllowedFile(12L)).isTrue();
        assertThat(run.historyCharacters()).isEqualTo(7_500);
        assertThat(run.summaryCharacters()).isEqualTo(2_000);
        assertThat(run.searchSnippetCharacters()).isEqualTo(500);
        assertThat(run.documentReadCharacters()).isEqualTo(300);
        assertThat(run.toolResultCharacters()).isEqualTo(4_000);
        assertThat(run.inputTokens()).isEqualTo(1_000);
        assertThat(run.outputTokens()).isEqualTo(2_000);
        assertThat(run.totalTokens()).isEqualTo(3_000);
        assertThat(run.historyCompressed()).isTrue();
        assertThat(run.budgetReasons()).containsExactly("HISTORY_SUMMARIZED");

        AgentRunBudget budget = new AgentRunBudget(
                8, 6, 4_000, 12_000,
                8_000, 8_000, 2_000, 6_000,
                500, 50, 3, 3_000, java.time.Duration.ofSeconds(90));
        assertThat(run.shouldStopToolExpansion(budget)).isTrue();
    }

    @Test
    void shouldDeduplicateBudgetReasonsAndTrackTokenTotals() {
        AgentRun run = AgentRun.pending("run-reasons", 8L, "trace-reasons");
        run.start(NOW);

        run.addBudgetReason("TOKEN_BUDGET_EXHAUSTED");
        run.addBudgetReason("TOKEN_BUDGET_EXHAUSTED");
        run.recordModelUsage(10, 20, 0);

        assertThat(run.totalTokens()).isEqualTo(30);
        assertThat(run.budgetReasons()).containsExactly("TOKEN_BUDGET_EXHAUSTED");
    }

    @Test
    void shouldRecordFailureCodeOnFailAndTimeout() {
        AgentRun failed = AgentRun.pending("f", 8L, "trace-1");
        failed.start(NOW);
        failed.fail("AGENT_MODEL_UNAVAILABLE", NOW.plusSeconds(1));
        assertThat(failed.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(failed.failureCode()).isEqualTo("AGENT_MODEL_UNAVAILABLE");

        AgentRun timedOut = AgentRun.pending("t", 8L, "trace-1");
        timedOut.start(NOW);
        timedOut.timeout(NOW.plusSeconds(45));
        assertThat(timedOut.status()).isEqualTo(AgentRunStatus.TIMED_OUT);
        assertThat(timedOut.failureCode()).isEqualTo("AGENT_RUN_TIMEOUT");
    }

    @Test
    void shouldRecordPendingToolFailureWithoutChangingRunStatus() {
        AgentRun run = AgentRun.pending("run-1", 8L, "trace-1");
        run.start(NOW);

        run.recordToolFailure(AgentRun.KNOWLEDGE_SEARCH_FAILURE_CODE);

        assertThat(run.status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(run.pendingToolFailureCode())
                .isEqualTo(AgentRun.KNOWLEDGE_SEARCH_FAILURE_CODE);
    }

    @Test
    void shouldRecordAssistantMessageAndCancelRequest() {
        AgentRun run = AgentRun.pending("run-1", 8L, "trace-1");
        run.start(NOW);
        run.recordAssistantMessage(99L);
        run.requestCancel();

        assertThat(run.assistantMessageId()).isEqualTo(99L);
        assertThat(run.isCancelRequested()).isTrue();
    }

    @Test
    void firstInvalidPlanShouldOnlyCountWithoutConsumingExtraStep() {
        AgentRun run = AgentRun.pending("run-1", 8L, "trace-1");
        run.start(NOW);

        run.recordInvalidPlan(NOW);

        assertThat(run.status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(run.invalidPlanCount()).isEqualTo(1);
        assertThat(run.stepCount()).isZero();
    }

    @Test
    void secondInvalidPlanShouldTerminateRunWithPlanInvalidCode() {
        AgentRun run = AgentRun.pending("run-1", 8L, "trace-1");
        run.start(NOW);

        run.recordInvalidPlan(NOW);
        run.recordInvalidPlan(NOW.plusSeconds(1));

        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.failureCode()).isEqualTo(AgentRun.AGENT_RETRIEVAL_PLAN_INVALID);
        assertThat(run.pendingToolFailureCode()).isEqualTo(AgentRun.AGENT_RETRIEVAL_PLAN_INVALID);
    }

    @Test
    void normalTwoRetrievalRoundsShouldPass() {
        AgentRun run = AgentRun.pending("run-1", 8L, "trace-1");
        run.start(NOW);

        run.recordRetrievalExecution(new AgentRun.RetrievalExecution(
                RetrievalQueryType.MULTI_HOP, "MULTI_HOP", 2, 2,
                List.of(3, 5), List.of("m-1", "m-2", "m-3"), List.of("m-1", "m-3"),
                true, true, null));
        run.recordRetrievalExecution(new AgentRun.RetrievalExecution(
                RetrievalQueryType.SINGLE_HOP, "SINGLE_HOP", 1, 1,
                List.of(4), List.of("m-4"), List.of("m-4"),
                true, false, "RERANK_FAILED"));

        assertThat(run.status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(run.retrievalRoundCount()).isEqualTo(2);
        assertThat(run.retrievalExecutions()).hasSize(2);
        assertThat(run.retrievalExecutions().getFirst().strategyId()).isEqualTo("MULTI_HOP");
        assertThat(run.retrievalExecutions().getFirst().perQueryHitCount()).containsExactly(3, 5);
        assertThat(run.retrievalExecutions().getFirst().finalChunkIds()).containsExactly("m-1", "m-3");
        assertThat(run.retrievalExecutions().getLast().rerankApplied()).isFalse();
        assertThat(run.retrievalExecutions().getLast().fallbackCode()).isEqualTo("RERANK_FAILED");
    }

    @Test
    void thirdRetrievalRoundShouldBeRejected() {
        AgentRun run = AgentRun.pending("run-1", 8L, "trace-1");
        run.start(NOW);
        run.recordRetrievalExecution(new AgentRun.RetrievalExecution(
                RetrievalQueryType.SINGLE_HOP, "SINGLE_HOP", 1, 1,
                List.of(1), List.of("m-1"), List.of("m-1"), false, false, null));
        run.recordRetrievalExecution(new AgentRun.RetrievalExecution(
                RetrievalQueryType.SINGLE_HOP, "SINGLE_HOP", 1, 1,
                List.of(1), List.of("m-2"), List.of("m-2"), false, false, null));

        assertThatThrownBy(() -> run.recordRetrievalExecution(new AgentRun.RetrievalExecution(
                RetrievalQueryType.SINGLE_HOP, "SINGLE_HOP", 1, 1,
                List.of(1), List.of("m-3"), List.of("m-3"), false, false, null)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(run.retrievalRoundCount()).isEqualTo(2);
        assertThat(run.status()).isEqualTo(AgentRunStatus.RUNNING);
    }
}
