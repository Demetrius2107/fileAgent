package com.demetrius.fileagent.agent.infrastructure.persistence;

import com.demetrius.fileagent.agent.domain.run.AgentRunEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Agent Run 的 active slot 唯一约束和执行租约 CAS 的 H2 持久化验证。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
@DataJpaTest
class AgentPersistenceJpaTest {

    @Autowired
    private AgentRunJpaRepository agentRunJpaRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void shouldRejectSecondActiveRunInSameSession() {
        agentRunJpaRepository.saveAndFlush(pending("run-1", 10L));

        assertThatThrownBy(() -> agentRunJpaRepository.saveAndFlush(pending("run-2", 10L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldAcquireLeaseOnlyOnceForExpectedVersion() {
        AgentRunEntity run = agentRunJpaRepository.saveAndFlush(pending("run-lease", 20L));
        Instant now = Instant.parse("2026-10-09T08:00:00Z");

        assertThat(agentRunJpaRepository.acquireLease(
                run.getRunId(), run.getVersion(), "node-a", now, now.plusSeconds(30))).isEqualTo(1);
        assertThat(agentRunJpaRepository.acquireLease(
                run.getRunId(), run.getVersion(), "node-b", now, now.plusSeconds(30))).isZero();
    }

    @Test
    void shouldFindPendingAndStaleRunningRunsForRecovery() {
        AgentRunEntity pending = agentRunJpaRepository.saveAndFlush(pending("run-pending", 30L));
        AgentRunEntity running = agentRunJpaRepository.saveAndFlush(pending("run-running", 31L));
        running.start(Instant.parse("2026-10-09T07:00:00Z"));
        agentRunJpaRepository.saveAndFlush(running);
        AgentRunEntity completed = agentRunJpaRepository.saveAndFlush(pending("run-completed", 32L));
        completed.start(Instant.parse("2026-10-09T07:00:00Z"));
        completed.succeed(1L, Instant.parse("2026-10-09T07:00:01Z"));
        agentRunJpaRepository.saveAndFlush(completed);

        assertThat(agentRunJpaRepository.findRecoverable(Instant.parse("2026-10-09T08:00:00Z")))
                .extracting(AgentRunEntity::getRunId)
                .contains(pending.getRunId(), running.getRunId())
                .doesNotContain(completed.getRunId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldRejectStaleRunVersionUpdate() {
        AgentRunEntity persisted = inNewTransaction(() -> agentRunJpaRepository.saveAndFlush(pending("run-version", 40L)));
        AgentRunEntity stale = inNewTransaction(() -> agentRunJpaRepository.findById(persisted.getRunId())
                .orElseThrow());

        inNewTransaction(() -> {
            AgentRunEntity current = agentRunJpaRepository.findById(persisted.getRunId()).orElseThrow();
            current.start(Instant.parse("2026-10-09T08:00:00Z"));
            return agentRunJpaRepository.saveAndFlush(current);
        });

        stale.start(Instant.parse("2026-10-09T08:00:01Z"));
        assertThatThrownBy(() -> inNewTransaction(() -> agentRunJpaRepository.saveAndFlush(stale)))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    private AgentRunEntity pending(String runId, Long sessionId) {
        return AgentRunEntity.pending(runId, sessionId, "trace-" + runId,
                "local-operator", "cipher-" + runId, "hmac-" + runId);
    }

    private <T> T inNewTransaction(Supplier<T> action) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> action.get());
    }
}
