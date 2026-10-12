package com.demetrius.fileagent.agent.domain.approval;

import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import com.demetrius.fileagent.api.enums.ApprovalStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 审批实体的决定约束验证。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-12
 */
class AgentApprovalEntityTest {

    private static final Instant NOW = Instant.parse("2026-10-12T08:00:00Z");

    @Test
    void shouldRemoveAllControlCharactersFromDecisionComment() {
        AgentApprovalEntity approval = pending();

        approval.decide(ApprovalStatus.APPROVED, "local-operator",
                "允许\n本次\t操作\u0000", "USER_APPROVED", NOW.plusSeconds(1));

        assertThat(approval.getDecisionComment()).isEqualTo("允许本次操作");
    }

    private AgentApprovalEntity pending() {
        return AgentApprovalEntity.pending(
                "approval-1", "run-1", 1L, "tool-call-1", "managed_http_action",
                "v1", "schema-v1", "endpoint-v1", "endpoint-hash", "policy-v1",
                ActionRiskLevel.HIGH_RISK_WRITE, "resource:demo", "input-hmac", "operation-1",
                "向受控系统提交一条记录", "将创建一条外部记录", "{\"name\":\"***\"}",
                NOW.plusSeconds(30), NOW);
    }
}
