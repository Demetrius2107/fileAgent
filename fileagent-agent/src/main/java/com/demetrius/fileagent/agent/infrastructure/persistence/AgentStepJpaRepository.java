package com.demetrius.fileagent.agent.infrastructure.persistence;

import com.demetrius.fileagent.agent.domain.step.AgentStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Agent 步骤审计事实的 Spring Data JPA 仓储。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
public interface AgentStepJpaRepository extends JpaRepository<AgentStepEntity, Long> {

    List<AgentStepEntity> findByRunIdOrderBySequenceNoAsc(String runId);
}
