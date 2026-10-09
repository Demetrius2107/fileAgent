package com.demetrius.fileagent.agent.infrastructure.persistence;

import com.demetrius.fileagent.agent.domain.checkpoint.AgentCheckpointEntity;
import com.demetrius.fileagent.agent.domain.checkpoint.AgentCheckpointRepository;
import com.demetrius.fileagent.agent.domain.run.AgentRunEntity;
import com.demetrius.fileagent.agent.domain.run.AgentRunRepository;
import com.demetrius.fileagent.agent.domain.step.AgentStepEntity;
import com.demetrius.fileagent.agent.domain.step.AgentStepRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Agent 三类持久化事实的领域仓储适配器，集中隔离 Spring Data API。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
@Component
@RequiredArgsConstructor
public class AgentPersistenceAdapter implements AgentRunRepository, AgentStepRepository, AgentCheckpointRepository {

    private final AgentRunJpaRepository agentRunJpaRepository;
    private final AgentStepJpaRepository agentStepJpaRepository;
    private final AgentCheckpointJpaRepository agentCheckpointJpaRepository;

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
    public AgentStepEntity save(AgentStepEntity step) {
        return agentStepJpaRepository.save(step);
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
}
