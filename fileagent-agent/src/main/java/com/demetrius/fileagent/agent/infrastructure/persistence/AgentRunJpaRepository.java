package com.demetrius.fileagent.agent.infrastructure.persistence;

import com.demetrius.fileagent.agent.domain.run.AgentRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Agent Run 的 Spring Data JPA 查询和租约 CAS 更新。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
public interface AgentRunJpaRepository extends JpaRepository<AgentRunEntity, String> {

    Optional<AgentRunEntity> findByActiveSlot(String activeSlot);

    @Query("select r from AgentRunEntity r where r.status = 'PENDING' or "
            + "(r.status = 'RUNNING' and (r.leaseExpiresAt is null or r.leaseExpiresAt < :now))")
    List<AgentRunEntity> findRecoverable(@Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AgentRunEntity r set r.executionOwner = :owner, r.leaseExpiresAt = :until, "
            + "r.version = r.version + 1 where r.runId = :runId and r.version = :version "
            + "and (r.leaseExpiresAt is null or r.leaseExpiresAt < :now)")
    int acquireLease(@Param("runId") String runId, @Param("version") long version,
                     @Param("owner") String owner, @Param("now") Instant now,
                     @Param("until") Instant until);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AgentRunEntity r set r.leaseExpiresAt = :until, r.version = r.version + 1 "
            + "where r.runId = :runId and r.executionOwner = :owner and r.leaseExpiresAt > :now")
    int renewLease(@Param("runId") String runId, @Param("owner") String owner,
                   @Param("now") Instant now, @Param("until") Instant until);
}
