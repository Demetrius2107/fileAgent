package com.demetrius.fileagent.action.infrastructure.persistence;

import com.demetrius.fileagent.action.domain.ActionToolExecutionEntity;
import com.demetrius.fileagent.api.dto.action.ActionExecutionCommand;
import com.demetrius.fileagent.api.enums.ActionExecutionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 副作用执行账本的唯一约束和原子抢占语义测试。
 *
 * @author raosaijie
 */
@DataJpaTest
class ActionToolExecutionJpaTest {

    private static final Instant NOW = Instant.parse("2026-10-20T03:00:00Z");

    @Autowired
    private ActionToolExecutionJpaRepository repository;

    @Test
    void shouldRejectDuplicateExecutionKey() {
        repository.saveAndFlush(pending("execution-1", "execution-key", "fingerprint-1", "operation-1"));

        assertThatThrownBy(() -> repository.saveAndFlush(
                pending("execution-2", "execution-key", "fingerprint-2", "operation-2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRejectDuplicateOperationId() {
        repository.saveAndFlush(pending("execution-1", "execution-key-1", "fingerprint-1", "operation"));

        assertThatThrownBy(() -> repository.saveAndFlush(
                pending("execution-2", "execution-key-2", "fingerprint-2", "operation")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRejectDuplicateSemanticOperationWithinRun() {
        repository.saveAndFlush(pending("execution-1", "execution-key-1", "fingerprint", "operation-1"));

        assertThatThrownBy(() -> repository.saveAndFlush(
                pending("execution-2", "execution-key-2", "fingerprint", "operation-2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldAllowOnlyOnePendingExecutionToBegin() {
        ActionToolExecutionEntity pending = repository.saveAndFlush(
                pending("execution-claim", "execution-key-claim", "fingerprint-claim", "operation-claim"));

        assertThat(repository.beginPending(pending.getExecutionId(), pending.getVersion(), NOW)).isEqualTo(1);
        assertThat(repository.beginPending(pending.getExecutionId(), pending.getVersion(), NOW)).isZero();

        ActionToolExecutionEntity executing = repository.findById(pending.getExecutionId()).orElseThrow();
        assertThat(executing.getStatus()).isEqualTo(ActionExecutionStatus.EXECUTING);
        assertThat(executing.getAttempt()).isEqualTo(1);
    }

    @Test
    void outcomeUnknownShouldNeverBeClaimedAgain() {
        ActionToolExecutionEntity pending = repository.saveAndFlush(
                pending("execution-unknown", "execution-key-unknown", "fingerprint-unknown", "operation-unknown"));
        assertThat(repository.beginPending(pending.getExecutionId(), pending.getVersion(), NOW)).isEqualTo(1);
        ActionToolExecutionEntity executing = repository.findById(pending.getExecutionId()).orElseThrow();
        executing.markOutcomeUnknown("HTTP_OUTCOME_UNKNOWN", NOW.plusSeconds(1));
        ActionToolExecutionEntity unknown = repository.saveAndFlush(executing);

        assertThat(repository.beginPending(unknown.getExecutionId(), unknown.getVersion(), NOW.plusSeconds(2))).isZero();
        assertThat(repository.findById(unknown.getExecutionId()).orElseThrow().getStatus())
                .isEqualTo(ActionExecutionStatus.OUTCOME_UNKNOWN);
    }

    private ActionToolExecutionEntity pending(String executionId, String executionKey,
                                              String fingerprint, String operationId) {
        ActionExecutionCommand command = new ActionExecutionCommand(
                "run-1", "tool-call-1", "call_registered_http_api", "http-action-v1",
                "create-expense-draft", "definition-v1", "endpoint-v1", "config-hmac",
                "policy-v1", executionKey, fingerprint, operationId,
                "{\"amount\":100}", "input-hmac", "local-operator",
                Set.of("expense:draft"), "expense:draft");
        return ActionToolExecutionEntity.pending(executionId, command, NOW);
    }
}
