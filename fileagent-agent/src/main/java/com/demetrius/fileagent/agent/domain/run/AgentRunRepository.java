package com.demetrius.fileagent.agent.domain.run;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Agent Run 的领域仓储契约，不暴露 Spring Data 实现细节。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
public interface AgentRunRepository {

    AgentRunEntity save(AgentRunEntity run);

    Optional<AgentRunEntity> findByRunId(String runId);

    Optional<AgentRunEntity> findActiveBySessionId(Long sessionId);

    List<AgentRunEntity> findRecoverable(Instant now);

    boolean acquireLease(String runId, long expectedVersion, String owner, Instant now, Instant until);
}
