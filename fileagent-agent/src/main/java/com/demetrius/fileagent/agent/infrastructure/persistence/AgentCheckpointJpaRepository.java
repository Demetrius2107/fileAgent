package com.demetrius.fileagent.agent.infrastructure.persistence;

import com.demetrius.fileagent.agent.domain.checkpoint.AgentCheckpointEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * Agent 最新恢复检查点的 Spring Data JPA 仓储。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
public interface AgentCheckpointJpaRepository extends JpaRepository<AgentCheckpointEntity, String> {

    @Query("select c.runId from AgentCheckpointEntity c")
    List<String> findAllRunIds();
}
