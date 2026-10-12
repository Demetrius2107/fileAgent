package com.demetrius.fileagent.agent.infrastructure.persistence;

import com.demetrius.fileagent.agent.domain.approval.AgentApprovalEntity;
import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import com.demetrius.fileagent.api.enums.ApprovalStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 审批持久化的 CAS 语义验证，防止并发决定重复恢复同一工具调用。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-12
 */
@DataJpaTest
class AgentApprovalJpaTest {

    private static final Instant NOW = Instant.parse("2026-10-12T08:00:00Z");

    @Autowired
    private AgentApprovalJpaRepository agentApprovalJpaRepository;

    @Test
    void shouldAllowOnlyOneDecisionForSamePendingApprovalVersion() {
        AgentApprovalEntity approval = agentApprovalJpaRepository.saveAndFlush(pending("approval-decision"));

        assertThat(agentApprovalJpaRepository.decidePending(
                approval.getApprovalId(), approval.getVersion(), ApprovalStatus.APPROVED,
                "local-operator", "允许本次操作", "USER_APPROVED", NOW)).isEqualTo(1);
        assertThat(agentApprovalJpaRepository.decidePending(
                approval.getApprovalId(), approval.getVersion(), ApprovalStatus.DENIED,
                "local-operator", "拒绝本次操作", "USER_DENIED", NOW.plusSeconds(1))).isZero();

        AgentApprovalEntity resolved = agentApprovalJpaRepository.findById(approval.getApprovalId()).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(resolved.getDecidedBy()).isEqualTo("local-operator");
        assertThat(resolved.getResolutionReason()).isEqualTo("USER_APPROVED");
    }

    @Test
    void shouldExpireOnlyPendingApprovalBeforeItsDeadline() {
        AgentApprovalEntity approval = agentApprovalJpaRepository.saveAndFlush(pending("approval-expire"));

        assertThat(agentApprovalJpaRepository.expirePending(
                approval.getApprovalId(), approval.getVersion(), NOW.plusSeconds(31), "APPROVAL_EXPIRED"))
                .isEqualTo(1);
        assertThat(agentApprovalJpaRepository.expirePending(
                approval.getApprovalId(), approval.getVersion(), NOW.plusSeconds(32), "APPROVAL_EXPIRED"))
                .isZero();

        AgentApprovalEntity expired = agentApprovalJpaRepository.findById(approval.getApprovalId()).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(expired.getResolutionReason()).isEqualTo("APPROVAL_EXPIRED");
    }

    private AgentApprovalEntity pending(String approvalId) {
        return AgentApprovalEntity.pending(
                approvalId,
                "run-" + approvalId,
                1L,
                "tool-call-" + approvalId,
                "managed_http_action",
                "v1",
                "schema-v1",
                "endpoint-v1",
                "endpoint-hash",
                "policy-v1",
                ActionRiskLevel.HIGH_RISK_WRITE,
                "resource:demo",
                "input-hmac",
                "operation-" + approvalId,
                "向受控系统提交一条记录",
                "将创建一条外部记录",
                "{\"name\":\"***\"}",
                NOW.plusSeconds(30),
                NOW);
    }
}
