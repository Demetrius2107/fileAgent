package com.demetrius.fileagent.agent.infrastructure.persistence;

import com.demetrius.fileagent.agent.domain.checkpoint.AgentCheckpointEntity;
import com.demetrius.fileagent.agent.domain.checkpoint.AgentCheckpointRepository;
import com.demetrius.fileagent.agent.domain.approval.AgentApprovalEntity;
import com.demetrius.fileagent.agent.domain.approval.AgentApprovalRepository;
import com.demetrius.fileagent.agent.domain.run.AgentRunEntity;
import com.demetrius.fileagent.agent.domain.run.AgentRunRepository;
import com.demetrius.fileagent.agent.domain.step.AgentStepEntity;
import com.demetrius.fileagent.agent.domain.step.AgentStepRepository;
import com.demetrius.fileagent.api.enums.ApprovalStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Agent 三类持久化事实的领域仓储适配器，集中隔离 Spring Data API。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
@Component
@RequiredArgsConstructor
public class AgentPersistenceAdapter implements AgentRunRepository, AgentStepRepository, AgentCheckpointRepository,
        AgentApprovalRepository {

    private final AgentRunJpaRepository agentRunJpaRepository;
    private final AgentStepJpaRepository agentStepJpaRepository;
    private final AgentCheckpointJpaRepository agentCheckpointJpaRepository;
    private final AgentApprovalJpaRepository agentApprovalJpaRepository;

    @Override
    public AgentRunEntity save(AgentRunEntity run) {
        return agentRunJpaRepository.save(run);
    }

    @Override
    public Optional<AgentRunEntity> findByRunId(String runId) {
        return agentRunJpaRepository.findById(runId);
    }

    @Override
    public Optional<AgentRunEntity> findActiveBySessionId(Long sessionId) {
        return agentRunJpaRepository.findByActiveSlot("session:" + sessionId);
    }

    @Override
    public List<AgentRunEntity> findRecoverable(Instant now) {
        return agentRunJpaRepository.findRecoverable(now);
    }

    @Override
    public boolean acquireLease(String runId, long expectedVersion, String owner, Instant now, Instant until) {
        return agentRunJpaRepository.acquireLease(runId, expectedVersion, owner, now, until) == 1;
    }

    @Override
    public boolean renewLease(String runId, String owner, Instant now, Instant until) {
        return agentRunJpaRepository.renewLease(runId, owner, now, until) == 1;
    }

    @Override
    public AgentStepEntity save(AgentStepEntity step) {
        return agentStepJpaRepository.save(step);
    }

    @Override
    public Optional<AgentStepEntity> findById(Long stepId) {
        return agentStepJpaRepository.findById(stepId);
    }

    @Override
    public List<AgentStepEntity> findStepsByRunId(String runId) {
        return agentStepJpaRepository.findByRunIdOrderBySequenceNoAsc(runId);
    }

    @Override
    public AgentCheckpointEntity save(AgentCheckpointEntity checkpoint) {
        return agentCheckpointJpaRepository.save(checkpoint);
    }

    @Override
    public Optional<AgentCheckpointEntity> findCheckpointByRunId(String runId) {
        return agentCheckpointJpaRepository.findById(runId);
    }

    @Override
    public void deleteByRunId(String runId) {
        agentCheckpointJpaRepository.deleteById(runId);
    }

    @Override
    public Set<String> findRunIds() {
        return new LinkedHashSet<>(agentCheckpointJpaRepository.findAllRunIds());
    }

    @Override
    public AgentApprovalEntity save(AgentApprovalEntity approval) {
        return agentApprovalJpaRepository.save(approval);
    }

    @Override
    public Optional<AgentApprovalEntity> findByApprovalId(String approvalId) {
        return agentApprovalJpaRepository.findById(approvalId);
    }

    @Override
    public Optional<AgentApprovalEntity> findByRunIdAndToolCallId(String runId, String toolCallId) {
        return agentApprovalJpaRepository.findByRunIdAndToolCallId(runId, toolCallId);
    }

    @Override
    public Optional<AgentApprovalEntity> findPendingByRunId(String runId) {
        return agentApprovalJpaRepository.findFirstByRunIdAndStatusOrderByCreatedAtDesc(runId,
                ApprovalStatus.PENDING);
    }

    @Override
    public Optional<AgentApprovalEntity> findLatestResolvedByRunId(String runId) {
        return agentApprovalJpaRepository.findFirstByRunIdAndStatusInOrderByUpdatedAtDesc(runId,
                List.of(ApprovalStatus.APPROVED, ApprovalStatus.DENIED));
    }

    @Override
    public List<AgentApprovalEntity> findPendingExpiredAt(Instant now) {
        return agentApprovalJpaRepository.findByStatusAndExpiresAtLessThanEqual(ApprovalStatus.PENDING, now);
    }

    @Override
    public boolean decidePending(String approvalId, long expectedVersion, ApprovalStatus decision,
                                 String actorId, String comment, String reason, Instant now) {
        return agentApprovalJpaRepository.decidePending(approvalId, expectedVersion, decision,
                actorId, comment, reason, now) == 1;
    }

    @Override
    public boolean expirePending(String approvalId, long expectedVersion, Instant now, String reason) {
        return agentApprovalJpaRepository.expirePending(approvalId, expectedVersion, now, reason) == 1;
    }

    @Override
    public boolean cancelPending(String approvalId, long expectedVersion, Instant now, String reason) {
        return agentApprovalJpaRepository.cancelPending(approvalId, expectedVersion, now, reason) == 1;
    }
}
