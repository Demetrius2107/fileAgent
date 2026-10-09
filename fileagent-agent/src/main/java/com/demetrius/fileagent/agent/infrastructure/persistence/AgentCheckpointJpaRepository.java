package com.demetrius.fileagent.agent.infrastructure.persistence;

import com.demetrius.fileagent.agent.domain.checkpoint.AgentCheckpointEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Agent 最新恢复检查点的 Spring Data JPA 仓储。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
public interface AgentCheckpointJpaRepository extends JpaRepository<AgentCheckpointEntity, String> {
}
