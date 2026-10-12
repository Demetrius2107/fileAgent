package com.demetrius.fileagent.agent.domain.approval;

import com.demetrius.fileagent.api.enums.ApprovalStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Agent 审批事实的领域仓储契约，暴露版本条件的决定与过期 CAS。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-12
 */
public interface AgentApprovalRepository {

    AgentApprovalEntity save(AgentApprovalEntity approval);

    Optional<AgentApprovalEntity> findByApprovalId(String approvalId);

    Optional<AgentApprovalEntity> findPendingByRunId(String runId);

    List<AgentApprovalEntity> findPendingExpiredAt(Instant now);

    boolean decidePending(String approvalId, long expectedVersion, ApprovalStatus decision,
                          String actorId, String comment, String reason, Instant now);

    boolean expirePending(String approvalId, long expectedVersion, Instant now, String reason);
}
