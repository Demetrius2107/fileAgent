package com.demetrius.fileagent.agent.application;

import com.demetrius.fileagent.agent.application.runtime.AgentRunCommandCodec;
import com.demetrius.fileagent.agent.application.runtime.AgentRunEventHub;
import com.demetrius.fileagent.agent.application.runtime.AgentRunExecutionCoordinator;
import com.demetrius.fileagent.agent.domain.run.AgentRunEntity;
import com.demetrius.fileagent.api.dto.AgentApprovalDecision;
import com.demetrius.fileagent.api.dto.AgentApprovalView;
import com.demetrius.fileagent.api.dto.AgentRunCommand;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import com.demetrius.fileagent.api.dto.KnowledgeScope;
import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import com.demetrius.fileagent.api.enums.ApprovalStatus;
import com.demetrius.fileagent.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

/**
 * 审批提交到后台续跑的应用层时序验证。
 *
 * @author raosaijie
 */
class AgentApprovalWorkflowServiceTest {

    @Test
    void shouldDecideBeforeReservingCapacityThenPublishAndEnqueueAfterCommit() {
        String runId = "run-approval";
        AgentApprovalService approvalService = mock(AgentApprovalService.class);
        AgentRunExecutionCoordinator executionCoordinator = mock(AgentRunExecutionCoordinator.class);
        AgentRunCommandCodec commandCodec = mock(AgentRunCommandCodec.class);
        AgentRunEventHub eventHub = mock(AgentRunEventHub.class);
        AgentRunEntity run = AgentRunEntity.pending(runId, 1L, "trace-1", "local-operator", "cipher", "hmac");
        AgentApprovalView approval = approval();
        AgentRunCommand command = new AgentRunCommand(runId, 1L, "trace-1", "执行测试动作",
                List.of(), KnowledgeScope.global());
        when(executionCoordinator.tryReserveCapacity(runId)).thenReturn(true);
        when(approvalService.decide(eq(runId), eq("approval-1"), any(), eq("local-operator")))
                .thenReturn(new AgentApprovalService.DecisionOutcome(approval, run, true));
        when(commandCodec.decrypt(run)).thenReturn(command);
        AgentApprovalWorkflowService service = new AgentApprovalWorkflowService(
                approvalService, executionCoordinator, commandCodec, eventHub);

        AgentApprovalView result = service.decide(runId, "approval-1",
                new AgentApprovalDecision(AgentApprovalDecision.Decision.APPROVE, "确认执行"));

        assertThat(result).isEqualTo(approval);
        ArgumentCaptor<AgentRunEvent> eventCaptor = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventHub).publish(eventCaptor.capture(), any(Long.class));
        assertThat(eventCaptor.getValue().type()).isEqualTo("approval.resolved");
        assertThat(eventCaptor.getValue().approval()).isEqualTo(approval);
        verify(executionCoordinator).enqueue(command);
        InOrder order = inOrder(approvalService, executionCoordinator);
        order.verify(approvalService).decide(eq(runId), eq("approval-1"), any(), eq("local-operator"));
        order.verify(executionCoordinator).tryReserveCapacity(runId);
    }

    @Test
    void shouldRejectAndRollBackFirstDecisionWhenCapacityIsUnavailable() {
        String runId = "run-full";
        AgentApprovalService approvalService = mock(AgentApprovalService.class);
        AgentRunExecutionCoordinator executionCoordinator = mock(AgentRunExecutionCoordinator.class);
        AgentRunEntity run = AgentRunEntity.pending(runId, 1L, "trace-1", "local-operator", "cipher", "hmac");
        when(approvalService.decide(eq(runId), eq("approval-1"), any(), eq("local-operator")))
                .thenReturn(new AgentApprovalService.DecisionOutcome(approval(), run, true));
        when(executionCoordinator.tryReserveCapacity(runId)).thenReturn(false);
        AgentApprovalWorkflowService service = new AgentApprovalWorkflowService(
                approvalService, executionCoordinator, mock(AgentRunCommandCodec.class), mock(AgentRunEventHub.class));

        assertThatThrownBy(() -> service.decide(runId, "approval-1",
                new AgentApprovalDecision(AgentApprovalDecision.Decision.DENY, null)))
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).getCode())
                .isEqualTo(429);

        verify(approvalService).decide(eq(runId), eq("approval-1"), any(), eq("local-operator"));
    }

    @Test
    void shouldReturnRepeatedSameDecisionWithoutReservingOrReenqueuing() {
        String runId = "run-duplicate";
        AgentApprovalService approvalService = mock(AgentApprovalService.class);
        AgentRunExecutionCoordinator executionCoordinator = mock(AgentRunExecutionCoordinator.class);
        AgentRunCommandCodec commandCodec = mock(AgentRunCommandCodec.class);
        AgentRunEventHub eventHub = mock(AgentRunEventHub.class);
        AgentRunEntity run = AgentRunEntity.pending(runId, 1L, "trace-1", "local-operator", "cipher", "hmac");
        AgentApprovalView approval = approval();
        when(approvalService.decide(eq(runId), eq("approval-1"), any(), eq("local-operator")))
                .thenReturn(new AgentApprovalService.DecisionOutcome(approval, run, false));
        AgentApprovalWorkflowService service = new AgentApprovalWorkflowService(
                approvalService, executionCoordinator, commandCodec, eventHub);

        AgentApprovalView result = service.decide(runId, "approval-1",
                new AgentApprovalDecision(AgentApprovalDecision.Decision.APPROVE, null));

        assertThat(result).isEqualTo(approval);
        verifyNoInteractions(executionCoordinator, commandCodec, eventHub);
    }

    private AgentApprovalView approval() {
        return new AgentApprovalView("approval-1", "managed_http_action", ActionRiskLevel.HIGH_RISK_WRITE,
                "执行测试动作", "resource:demo", "影响一条演示记录", "{\"payload\":\"***\"}",
                "input-hmac", Instant.parse("2026-10-12T08:05:00Z"), ApprovalStatus.APPROVED);
    }
}
