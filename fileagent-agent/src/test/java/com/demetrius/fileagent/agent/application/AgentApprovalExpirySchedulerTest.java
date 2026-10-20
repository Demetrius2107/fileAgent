package com.demetrius.fileagent.agent.application;

import com.demetrius.fileagent.agent.application.runtime.AgentRunEventHub;
import com.demetrius.fileagent.agent.domain.run.AgentRunEntity;
import com.demetrius.fileagent.api.dto.AgentApprovalView;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.enums.ApprovalStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 无客户端请求时审批到期扫描的事件收敛验证。
 *
 * @author raosaijie
 */
class AgentApprovalExpirySchedulerTest {

    @Test
    void shouldPublishTimedOutStatusForEveryExpiredApproval() {
        AgentApprovalService approvalService = mock(AgentApprovalService.class);
        AgentRunEventHub eventHub = mock(AgentRunEventHub.class);
        AgentRunEntity run = AgentRunEntity.pending("run-expire", 1L, "trace-1", "local-operator", "cipher", "hmac");
        run.start(Instant.parse("2026-10-12T08:00:00Z"));
        run.waitForApproval(Instant.parse("2026-10-12T08:00:01Z"));
        run.timeout(Instant.parse("2026-10-12T08:05:00Z"));
        AgentApprovalView approval = new AgentApprovalView("approval-1", "managed_http_action",
                ActionRiskLevel.HIGH_RISK_WRITE, "执行测试动作", "resource:demo", "影响一条演示记录",
                "{\"payload\":\"***\"}", "input-hmac", Instant.parse("2026-10-12T08:05:00Z"),
                ApprovalStatus.EXPIRED);
        when(approvalService.expirePendingApprovals())
                .thenReturn(List.of(new AgentApprovalService.ExpirationOutcome(approval, run)));
        AgentApprovalExpiryScheduler scheduler = new AgentApprovalExpiryScheduler(approvalService, eventHub);

        scheduler.expireNow();

        ArgumentCaptor<AgentRunEvent> eventCaptor = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventHub).publish(eventCaptor.capture(), anyLong());
        assertThat(eventCaptor.getValue().type()).isEqualTo("run.status");
        assertThat(eventCaptor.getValue().status()).isEqualTo(AgentRunStatus.TIMED_OUT);
        assertThat(eventCaptor.getValue().summary()).isEqualTo("审批已过期");
    }
}
