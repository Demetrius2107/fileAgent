package com.demetrius.fileagent.agent.domain.step;

import java.util.List;
import java.util.Optional;

/**
 * Agent 步骤审计事实的领域仓储契约。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
public interface AgentStepRepository {

    AgentStepEntity save(AgentStepEntity step);

    Optional<AgentStepEntity> findById(Long stepId);

    List<AgentStepEntity> findStepsByRunId(String runId);
}
