package com.demetrius.fileagent.agent.infrastructure.persistence;

import com.demetrius.fileagent.agent.domain.approval.AgentApprovalEntity;
import com.demetrius.fileagent.api.enums.ApprovalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Agent 审批的 Spring Data JPA 查询和版本条件决议更新。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-12
 */
public interface AgentApprovalJpaRepository extends JpaRepository<AgentApprovalEntity, String> {

    Optional<AgentApprovalEntity> findByRunIdAndToolCallId(String runId, String toolCallId);

    Optional<AgentApprovalEntity> findFirstByRunIdAndStatusOrderByCreatedAtDesc(String runId,
                                                                                  ApprovalStatus status);

    Optional<AgentApprovalEntity> findFirstByRunIdAndStatusInOrderByUpdatedAtDesc(
            String runId, List<ApprovalStatus> statuses);

    List<AgentApprovalEntity> findByStatusAndExpiresAtLessThanEqual(ApprovalStatus status, Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AgentApprovalEntity a set a.status = :decision, a.decidedBy = :actorId, "
            + "a.decisionComment = :comment, a.resolutionReason = :reason, a.updatedAt = :now, "
            + "a.version = a.version + 1 where a.approvalId = :approvalId and a.status = 'PENDING' "
            + "and a.version = :expectedVersion")
    int decidePending(@Param("approvalId") String approvalId,
                      @Param("expectedVersion") long expectedVersion,
                      @Param("decision") ApprovalStatus decision,
                      @Param("actorId") String actorId,
                      @Param("comment") String comment,
                      @Param("reason") String reason,
                      @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AgentApprovalEntity a set a.status = 'EXPIRED', a.resolutionReason = :reason, "
            + "a.updatedAt = :now, a.version = a.version + 1 where a.approvalId = :approvalId "
            + "and a.status = 'PENDING' and a.version = :expectedVersion and a.expiresAt <= :now")
    int expirePending(@Param("approvalId") String approvalId,
                      @Param("expectedVersion") long expectedVersion,
                      @Param("now") Instant now,
                      @Param("reason") String reason);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AgentApprovalEntity a set a.status = 'CANCELLED', a.resolutionReason = :reason, "
            + "a.updatedAt = :now, a.version = a.version + 1 where a.approvalId = :approvalId "
            + "and a.status = 'PENDING' and a.version = :expectedVersion")
    int cancelPending(@Param("approvalId") String approvalId,
                      @Param("expectedVersion") long expectedVersion,
                      @Param("now") Instant now,
                      @Param("reason") String reason);
}
