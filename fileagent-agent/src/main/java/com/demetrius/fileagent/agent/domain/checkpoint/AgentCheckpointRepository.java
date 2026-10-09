package com.demetrius.fileagent.agent.domain.checkpoint;

import java.util.Optional;

/**
 * 每个 Agent Run 最新恢复检查点的领域仓储契约。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
public interface AgentCheckpointRepository {

    AgentCheckpointEntity save(AgentCheckpointEntity checkpoint);

    Optional<AgentCheckpointEntity> findCheckpointByRunId(String runId);
}
