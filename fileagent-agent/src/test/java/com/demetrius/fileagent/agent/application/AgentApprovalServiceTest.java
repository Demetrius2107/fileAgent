package com.demetrius.fileagent.agent.application;

import com.demetrius.fileagent.agent.domain.approval.AgentApprovalEntity;
import com.demetrius.fileagent.agent.domain.approval.AgentApprovalRepository;
import com.demetrius.fileagent.agent.domain.policy.AgentActionCandidate;
import com.demetrius.fileagent.agent.domain.run.AgentRunEntity;
import com.demetrius.fileagent.agent.domain.run.AgentRunRepository;
import com.demetrius.fileagent.agent.domain.step.AgentStepEntity;
import com.demetrius.fileagent.agent.domain.step.AgentStepRepository;
import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import com.demetrius.fileagent.agent.infrastructure.state.StrictAgentStateLoader;
import com.demetrius.fileagent.api.dto.AgentApprovalDecision;
import com.demetrius.fileagent.api.dto.AgentApprovalView;
import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.enums.ApprovalStatus;
import com.demetrius.fileagent.common.exception.BizException;
import com.demetrius.fileagent.common.security.EnvelopeCrypto;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * Agent 审批暂停持久化的应用层编排验证。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-12
 */
class AgentApprovalServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T08:00:00Z");

    @Test
    void shouldVerifyCheckpointBeforePersistingWaitingStepApprovalAndRun() throws Exception {
        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        AgentStepRepository stepRepository = mock(AgentStepRepository.class);
        AgentApprovalRepository approvalRepository = mock(AgentApprovalRepository.class);
        StrictAgentStateLoader strictStateLoader = mock(StrictAgentStateLoader.class);
        EnvelopeCrypto envelopeCrypto = mock(EnvelopeCrypto.class);
        AgentProperties properties = new AgentProperties();
        properties.setApprovalTimeout(Duration.ofMinutes(5));
        AgentRunEntity run = running("run-approval");
        when(runRepository.findByRunId(run.getRunId())).thenReturn(Optional.of(run));
        when(strictStateLoader.loadVerified(run.getRunId())).thenReturn(askingState(run.getRunId()));
        when(stepRepository.findStepsByRunId(run.getRunId())).thenReturn(List.of());
        when(stepRepository.save(any(AgentStepEntity.class))).thenAnswer(invocation -> {
            AgentStepEntity step = invocation.getArgument(0);
            assignId(step, 9L);
            return step;
        });
        when(approvalRepository.save(any(AgentApprovalEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(envelopeCrypto.encrypt(any(), any())).thenReturn("ciphertext");
        AgentApprovalService service = new AgentApprovalService(
                runRepository, stepRepository, approvalRepository, strictStateLoader, envelopeCrypto,
                properties, Clock.fixed(NOW, ZoneOffset.UTC));

        AgentApprovalView view = service.pause(run.getRunId(), candidate());

        verify(strictStateLoader).loadVerified(run.getRunId());
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.WAITING_APPROVAL);
        assertThat(view.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(view.tool()).isEqualTo("managed_http_action");
        assertThat(view.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        ArgumentCaptor<AgentApprovalEntity> approvalCaptor = ArgumentCaptor.forClass(AgentApprovalEntity.class);
        verify(approvalRepository).save(approvalCaptor.capture());
        assertThat(approvalCaptor.getValue().getStepId()).isEqualTo(9L);
        assertThat(approvalCaptor.getValue().getCanonicalInputHmac()).isEqualTo("input-hmac");
        verify(envelopeCrypto).encrypt(eq("{\"payload\":\"demo\"}"),
                eq("agent_approval:run-approval:tool-call-1:input:v1"));
    }

    @Test
    void shouldAtomicallyApproveWaitingApprovalAndRequeueRunOnlyOnce() throws Exception {
        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        AgentStepRepository stepRepository = mock(AgentStepRepository.class);
        AgentApprovalRepository approvalRepository = mock(AgentApprovalRepository.class);
        StrictAgentStateLoader strictStateLoader = mock(StrictAgentStateLoader.class);
        AgentRunEntity run = running("run-approval");
        run.waitForApproval(NOW.minusSeconds(1));
        AgentStepEntity step = AgentStepEntity.waitingApproval(candidate(), 1, "ciphertext", NOW.minusSeconds(1));
        assignId(step, 9L);
        AgentApprovalEntity approval = pendingApproval(run.getRunId(), step.getId());
        when(runRepository.findByRunId(run.getRunId())).thenReturn(Optional.of(run));
        when(stepRepository.findById(step.getId())).thenReturn(Optional.of(step));
        when(strictStateLoader.loadVerified(run.getRunId())).thenReturn(askingState(run.getRunId()));
        when(approvalRepository.findByApprovalId("approval-1")).thenReturn(Optional.of(approval));
        when(approvalRepository.decidePending(eq("approval-1"), eq(0L), eq(ApprovalStatus.APPROVED),
                eq("local-operator"), eq(null), eq("USER_APPROVED"), eq(NOW)))
                .thenAnswer(invocation -> {
                    approval.decide(ApprovalStatus.APPROVED, "local-operator", null,
                            "USER_APPROVED", NOW);
                    return true;
                });
        when(stepRepository.save(any(AgentStepEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AgentApprovalService service = new AgentApprovalService(
                runRepository, stepRepository, approvalRepository, strictStateLoader,
                mock(EnvelopeCrypto.class), new AgentProperties(), Clock.fixed(NOW, ZoneOffset.UTC));

        AgentApprovalService.DecisionOutcome first = service.decide(run.getRunId(), "approval-1",
                new AgentApprovalDecision(AgentApprovalDecision.Decision.APPROVE, null), "local-operator");
        AgentApprovalService.DecisionOutcome duplicate = service.decide(run.getRunId(), "approval-1",
                new AgentApprovalDecision(AgentApprovalDecision.Decision.APPROVE, null), "local-operator");

        assertThat(first.requeued()).isTrue();
        assertThat(duplicate.requeued()).isFalse();
        assertThat(first.approval().status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.PENDING);
        assertThat(step.getStatus().name()).isEqualTo("RUNNING");
        verify(approvalRepository, times(1)).decidePending(eq("approval-1"), eq(0L),
                eq(ApprovalStatus.APPROVED), eq("local-operator"), eq(null), eq("USER_APPROVED"), eq(NOW));

        service.completeApprovedStep(run.getRunId(), "approval-1", "tool-call-1");

        assertThat(step.getStatus().name()).isEqualTo("SUCCEEDED");
        assertThat(step.getResultCode()).isEqualTo("ACTION_COMPLETED");
    }

    @Test
    void shouldExpireApprovalStepAndRunTogetherUsingApprovalCas() throws Exception {
        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        AgentStepRepository stepRepository = mock(AgentStepRepository.class);
        AgentApprovalRepository approvalRepository = mock(AgentApprovalRepository.class);
        AgentRunEntity run = running("run-expire");
        run.waitForApproval(NOW.minusSeconds(2));
        AgentActionCandidate candidate = candidateFor(run.getRunId());
        AgentStepEntity step = AgentStepEntity.waitingApproval(candidate, 1, "ciphertext", NOW.minusSeconds(2));
        assignId(step, 9L);
        AgentApprovalEntity approval = AgentApprovalEntity.pending(
                "approval-expire", run.getRunId(), step.getId(), candidate.toolCallId(), candidate.toolName(),
                candidate.toolVersion(), candidate.toolDefinitionVersion(), candidate.endpointRevision(),
                candidate.endpointConfigHash(), candidate.policyVersion(), candidate.riskLevel(),
                candidate.resourceScope(), candidate.canonicalInputHmac(), candidate.operationId(),
                candidate.safeSummary(), candidate.expectedImpact(), candidate.parameterPreview(),
                NOW.minusSeconds(1), NOW.minusSeconds(3));
        when(approvalRepository.findPendingExpiredAt(NOW)).thenReturn(List.of(approval));
        when(approvalRepository.expirePending("approval-expire", 0L, NOW, "APPROVAL_EXPIRED"))
                .thenReturn(true);
        when(runRepository.findByRunId(run.getRunId())).thenReturn(Optional.of(run));
        when(stepRepository.findById(step.getId())).thenReturn(Optional.of(step));
        when(stepRepository.save(any(AgentStepEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.save(any(AgentRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AgentApprovalService service = new AgentApprovalService(
                runRepository, stepRepository, approvalRepository, mock(StrictAgentStateLoader.class),
                mock(EnvelopeCrypto.class), new AgentProperties(), Clock.fixed(NOW, ZoneOffset.UTC));

        List<AgentApprovalService.ExpirationOutcome> outcomes = service.expirePendingApprovals();

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.getFirst().approval().status()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(step.getStatus().name()).isEqualTo("EXPIRED");
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.TIMED_OUT);
        assertThat(run.getActiveSlot()).isNull();
    }

    @Test
    void shouldRejectExpiredDecisionAndLeaveFinalizationToExpiryScanner() {
        AgentApprovalRepository approvalRepository = mock(AgentApprovalRepository.class);
        AgentApprovalEntity approval = expiredApproval("run-expired", 9L);
        when(approvalRepository.findByApprovalId("approval-expired")).thenReturn(Optional.of(approval));
        AgentApprovalService service = new AgentApprovalService(
                mock(AgentRunRepository.class), mock(AgentStepRepository.class), approvalRepository,
                mock(StrictAgentStateLoader.class), mock(EnvelopeCrypto.class), new AgentProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.decide("run-expired", "approval-expired",
                new AgentApprovalDecision(AgentApprovalDecision.Decision.APPROVE, null), "local-operator"))
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).getCode())
                .isEqualTo(409);

        verify(approvalRepository, never()).expirePending(anyString(), anyLong(), any(), anyString());
    }

    private AgentRunEntity running(String runId) {
        AgentRunEntity run = AgentRunEntity.pending(
                runId, 1L, "trace-1", "local-operator", "cipher", "hmac");
        run.start(NOW.minusSeconds(1));
        return run;
    }

    private AgentActionCandidate candidate() {
        return candidateFor("run-approval");
    }

    private AgentActionCandidate candidateFor(String runId) {
        return new AgentActionCandidate(
                runId, "tool-call-1", "managed_http_action", "tool-v1", "schema-v1",
                "endpoint-v1", "endpoint-hash", "policy-v1", ActionRiskLevel.HIGH_RISK_WRITE,
                "resource:demo", Set.of("resource:demo"), java.util.Map.of("payload", "demo"), false,
                "{\"payload\":\"demo\"}", "input-hmac", "operation-1",
                "执行测试动作", "影响一条演示记录", "{\"payload\":\"***\"}");
    }

    private AgentApprovalEntity pendingApproval(String runId, Long stepId) {
        AgentActionCandidate candidate = candidate();
        return AgentApprovalEntity.pending(
                "approval-1", runId, stepId, candidate.toolCallId(), candidate.toolName(),
                candidate.toolVersion(), candidate.toolDefinitionVersion(), candidate.endpointRevision(),
                candidate.endpointConfigHash(), candidate.policyVersion(), candidate.riskLevel(),
                candidate.resourceScope(), candidate.canonicalInputHmac(), candidate.operationId(),
                candidate.safeSummary(), candidate.expectedImpact(), candidate.parameterPreview(),
                NOW.plusSeconds(300), NOW.minusSeconds(1));
    }

    private AgentApprovalEntity expiredApproval(String runId, Long stepId) {
        AgentActionCandidate candidate = candidateFor(runId);
        return AgentApprovalEntity.pending(
                "approval-expired", runId, stepId, candidate.toolCallId(), candidate.toolName(),
                candidate.toolVersion(), candidate.toolDefinitionVersion(), candidate.endpointRevision(),
                candidate.endpointConfigHash(), candidate.policyVersion(), candidate.riskLevel(),
                candidate.resourceScope(), candidate.canonicalInputHmac(), candidate.operationId(),
                candidate.safeSummary(), candidate.expectedImpact(), candidate.parameterPreview(),
                NOW.minusSeconds(1), NOW.minusSeconds(2));
    }

    private AgentState askingState(String runId) {
        ToolUseBlock toolCall = new ToolUseBlock("tool-call-1", "managed_http_action",
                java.util.Map.of("payload", "demo"), null, java.util.Map.of(), ToolCallState.ASKING);
        return AgentState.builder()
                .sessionId(runId)
                .userId("local-operator")
                .context(List.of(Msg.builder().role(MsgRole.ASSISTANT).content(toolCall).build()))
                .build();
    }

    private void assignId(AgentStepEntity step, Long id) throws Exception {
        Field field = AgentStepEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(step, id);
    }
}
