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
import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.enums.ApprovalStatus;
import com.demetrius.fileagent.common.exception.BizException;
import com.demetrius.fileagent.common.security.EnvelopeCrypto;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Agent 审批应用服务：将已持久化的 ASKING 检查点收敛为 Step、Approval 和 Run 的业务事实。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-12
 */
@Service
@RequiredArgsConstructor
public class AgentApprovalService {

    private static final String REASON_APPROVAL_EXPIRED = "APPROVAL_EXPIRED";

    private final AgentRunRepository runRepository;
    private final AgentStepRepository stepRepository;
    private final AgentApprovalRepository approvalRepository;
    private final StrictAgentStateLoader strictStateLoader;
    private final EnvelopeCrypto envelopeCrypto;
    private final AgentProperties properties;
    private final Clock clock;

    /**
     * 将 AgentScope 已结束的 ASKING 回合持久化为等待审批状态。检查点缺失或损坏必须直接失败。
     */
    @Transactional
    public AgentApprovalView pause(String runId, AgentActionCandidate candidate) {
        requireCandidate(runId, candidate);
        requireAskingToolCall(strictStateLoader.loadVerified(runId), candidate.toolCallId(), candidate.toolName());
        AgentRunEntity run = requireRun(runId);
        AgentApprovalEntity existing = approvalRepository.findByRunIdAndToolCallId(runId, candidate.toolCallId())
                .orElse(null);
        if (existing != null) {
            return toView(existing);
        }
        if (run.getStatus() != AgentRunStatus.RUNNING) {
            throw new BizException(409, "Agent Run 当前不能进入审批状态: " + run.getStatus());
        }

        Instant now = clock.instant();
        String inputCiphertext = envelopeCrypto.encrypt(candidate.canonicalArguments(), inputAad(candidate));
        AgentStepEntity step = AgentStepEntity.waitingApproval(candidate,
                stepRepository.findStepsByRunId(runId).size() + 1, inputCiphertext, now);
        AgentStepEntity savedStep = stepRepository.save(step);
        if (savedStep.getId() == null) {
            throw new IllegalStateException("审批步骤保存后缺少主键");
        }
        AgentApprovalEntity approval = AgentApprovalEntity.pending(
                UUID.randomUUID().toString(), runId, savedStep.getId(), candidate.toolCallId(),
                candidate.toolName(), candidate.toolVersion(), candidate.toolDefinitionVersion(),
                candidate.endpointRevision(), candidate.endpointConfigHash(), candidate.policyVersion(),
                candidate.riskLevel(), candidate.resourceScope(), candidate.canonicalInputHmac(),
                candidate.operationId(), candidate.safeSummary(), candidate.expectedImpact(),
                candidate.parameterPreview(), now.plus(properties.getApprovalTimeout()), now);
        AgentApprovalEntity savedApproval = approvalRepository.save(approval);
        run.waitForApproval(now);
        runRepository.save(run);
        return toView(savedApproval);
    }

    /**
     * 原子记录一次批准或拒绝，并把 Run 放回待调度队列；重复的相同决定只返回既有结果。
     */
    @Transactional
    public DecisionOutcome decide(String runId, String approvalId, AgentApprovalDecision request, String actorId) {
        AgentApprovalEntity approval = requireApproval(approvalId);
        if (!Objects.equals(runId, approval.getRunId())) {
            throw new BizException(409, "审批不属于当前 Agent Run");
        }
        ApprovalStatus decision = toApprovalStatus(request);
        if (approval.getStatus() != ApprovalStatus.PENDING) {
            return existingDecision(approval, decision);
        }

        Instant now = clock.instant();
        if (!approval.getExpiresAt().isAfter(now)) {
            throw new BizException(409, "审批已过期");
        }
        requireAskingToolCall(strictStateLoader.loadVerified(runId), approval.getToolCallId(), approval.getToolName());
        AgentRunEntity run = requireRun(runId);
        if (run.getStatus() != AgentRunStatus.WAITING_APPROVAL) {
            throw new BizException(409, "Agent Run 当前不能处理审批: " + run.getStatus());
        }
        AgentStepEntity step = stepRepository.findById(approval.getStepId())
                .filter(value -> runId.equals(value.getRunId())
                        && approval.getToolCallId().equals(value.getToolCallId()))
                .orElseThrow(() -> new BizException(409, "审批步骤不存在或不匹配"));
        String reason = decision == ApprovalStatus.APPROVED ? "USER_APPROVED" : "USER_DENIED";
        boolean changed = approvalRepository.decidePending(approval.getApprovalId(), approval.getVersion(), decision,
                requireActor(actorId), AgentApprovalEntity.sanitizeComment(request.comment()), reason, now);
        if (!changed) {
            AgentApprovalEntity current = requireApproval(approvalId);
            return existingDecision(current, decision);
        }

        AgentApprovalEntity resolved = requireApproval(approvalId);
        if (decision == ApprovalStatus.APPROVED) {
            step.resumeAfterApproval();
        } else {
            step.cancel(now);
        }
        run.resumeAfterApproval(now);
        stepRepository.save(step);
        AgentRunEntity savedRun = runRepository.save(run);
        return new DecisionOutcome(toView(resolved), savedRun, true);
    }

    /**
     * 从已验证检查点中找回唯一的 ASKING ToolCall；不接受浏览器提供的工具或参数替换。
     */
    public static ToolUseBlock requireAskingToolCall(AgentState state, String toolCallId, String toolName) {
        if (state == null || toolCallId == null || toolCallId.isBlank() || toolName == null || toolName.isBlank()) {
            throw new IllegalStateException("Agent 审批检查点不完整");
        }
        return state.getContext().stream()
                .flatMap(message -> message.getContent().stream())
                .filter(ToolUseBlock.class::isInstance)
                .map(ToolUseBlock.class::cast)
                .filter(toolCall -> toolCallId.equals(toolCall.getId())
                        && toolName.equals(toolCall.getName())
                        && toolCall.getState() == ToolCallState.ASKING)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Agent 审批检查点缺少 ASKING 工具调用"));
    }

    /** 审批已通过但恢复前检测到工具绑定变化时，撤销该批准和原步骤，阻止旧授权继续生效。 */
    @Transactional
    public void invalidateApprovedBinding(String runId, String approvalId) {
        AgentApprovalEntity approval = requireApproval(approvalId);
        if (!runId.equals(approval.getRunId())) {
            throw new BizException(409, "审批不属于当前 Agent Run");
        }
        if (approval.getStatus() != ApprovalStatus.APPROVED) {
            return;
        }
        Instant now = clock.instant();
        AgentStepEntity step = stepRepository.findById(approval.getStepId())
                .orElseThrow(() -> new BizException(409, "审批步骤不存在"));
        approval.invalidateApproved("APPROVAL_BINDING_CHANGED", now);
        if (step.getStatus() == com.demetrius.fileagent.agent.domain.step.AgentStepStatus.RUNNING) {
            step.cancel(now);
            stepRepository.save(step);
        }
        approvalRepository.save(approval);
    }

    /**
     * 已批准的原始 ToolCall 正常完成后收敛对应审计步骤；迟到事件遇到终态步骤时不覆盖既有事实。
     */
    @Transactional
    public boolean completeApprovedStep(String runId, String approvalId, String toolCallId) {
        AgentStepEntity step = findRunningApprovedStep(runId, approvalId, toolCallId);
        if (step == null) {
            return false;
        }
        step.succeed("ACTION_COMPLETED", "受控动作已完成", clock.instant());
        stepRepository.save(step);
        return true;
    }

    /**
     * 已批准动作恢复执行异常时收敛对应审计步骤；Run 的失败状态仍由运行协调器统一处理。
     */
    @Transactional
    public boolean failApprovedStep(String runId, String approvalId, String toolCallId) {
        AgentStepEntity step = findRunningApprovedStep(runId, approvalId, toolCallId);
        if (step == null) {
            return false;
        }
        step.fail("ACTION_EXECUTION_FAILED", clock.instant());
        stepRepository.save(step);
        return true;
    }

    /**
     * 扫描并收敛已过期的待决审批。Approval 的版本条件 CAS 成功后才会迁移对应 Step 和 Run。
     */
    @Transactional
    public List<ExpirationOutcome> expirePendingApprovals() {
        Instant now = clock.instant();
        List<ExpirationOutcome> outcomes = new ArrayList<>();
        for (AgentApprovalEntity approval : approvalRepository.findPendingExpiredAt(now)) {
            ExpirationOutcome outcome = expirePendingApproval(approval, now);
            if (outcome != null) {
                outcomes.add(outcome);
            }
        }
        return List.copyOf(outcomes);
    }

    /**
     * Run 主动取消时撤销其唯一待决审批和原工具步骤；CAS 失败说明已有并发决议，不覆盖其结果。
     */
    @Transactional
    public boolean cancelPendingForRun(String runId, String reason) {
        if (runId == null || runId.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("取消审批参数不合法");
        }
        AgentApprovalEntity approval = approvalRepository.findPendingByRunId(runId).orElse(null);
        if (approval == null) {
            return false;
        }
        AgentStepEntity step = stepRepository.findById(approval.getStepId())
                .filter(value -> runId.equals(value.getRunId())
                        && approval.getToolCallId().equals(value.getToolCallId()))
                .orElseThrow(() -> new BizException(409, "审批步骤不存在或不匹配"));
        if (step.getStatus() != com.demetrius.fileagent.agent.domain.step.AgentStepStatus.WAITING_APPROVAL) {
            throw new IllegalStateException("待取消审批关联步骤状态不一致");
        }
        Instant now = clock.instant();
        if (!approvalRepository.cancelPending(approval.getApprovalId(), approval.getVersion(), now, reason)) {
            return false;
        }
        approval.cancel(reason, now);
        step.cancel(now);
        stepRepository.save(step);
        return true;
    }

    /** 将审批实体转换为公开脱敏视图，HMAC 只展示短指纹用于用户核对同一次参数。 */
    public AgentApprovalView toView(AgentApprovalEntity approval) {
        if (approval == null) {
            return null;
        }
        return new AgentApprovalView(
                approval.getApprovalId(), approval.getToolName(), approval.getRiskLevel(),
                approval.getActionSummary(), approval.getResourceScope(), approval.getExpectedImpact(),
                approval.getParameterPreview(), fingerprint(approval.getCanonicalInputHmac()),
                approval.getExpiresAt(), approval.getStatus());
    }

    private AgentRunEntity requireRun(String runId) {
        return runRepository.findByRunId(runId)
                .orElseThrow(() -> new BizException(404, "Agent Run 不存在: " + runId));
    }

    private AgentApprovalEntity requireApproval(String approvalId) {
        if (approvalId == null || approvalId.isBlank()) {
            throw new BizException("approvalId 不能为空");
        }
        return approvalRepository.findByApprovalId(approvalId)
                .orElseThrow(() -> new BizException(404, "审批不存在: " + approvalId));
    }

    private DecisionOutcome existingDecision(AgentApprovalEntity approval, ApprovalStatus requestedDecision) {
        if (approval.getStatus() == requestedDecision) {
            AgentRunEntity run = requireRun(approval.getRunId());
            return new DecisionOutcome(toView(approval), run, false);
        }
        throw new BizException(409, "审批已经处理为: " + approval.getStatus());
    }

    private ExpirationOutcome expirePendingApproval(AgentApprovalEntity approval, Instant now) {
        if (approval.getStatus() != ApprovalStatus.PENDING || approval.getExpiresAt().isAfter(now)) {
            return null;
        }
        AgentRunEntity run = requireRun(approval.getRunId());
        AgentStepEntity step = stepRepository.findById(approval.getStepId())
                .filter(value -> approval.getRunId().equals(value.getRunId())
                        && approval.getToolCallId().equals(value.getToolCallId()))
                .orElseThrow(() -> new BizException(409, "审批步骤不存在或不匹配"));
        if (run.getStatus() != AgentRunStatus.WAITING_APPROVAL
                || step.getStatus() != com.demetrius.fileagent.agent.domain.step.AgentStepStatus.WAITING_APPROVAL) {
            throw new IllegalStateException("过期审批关联状态不一致");
        }
        if (!approvalRepository.expirePending(approval.getApprovalId(), approval.getVersion(), now,
                REASON_APPROVAL_EXPIRED)) {
            return null;
        }
        approval.expire(REASON_APPROVAL_EXPIRED, now);
        step.expire(now);
        run.timeout(now);
        stepRepository.save(step);
        AgentRunEntity savedRun = runRepository.save(run);
        return new ExpirationOutcome(toView(approval), savedRun);
    }

    private AgentStepEntity findRunningApprovedStep(String runId, String approvalId, String toolCallId) {
        if (runId == null || runId.isBlank() || toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("审批步骤参数不合法");
        }
        AgentApprovalEntity approval = requireApproval(approvalId);
        if (!runId.equals(approval.getRunId()) || !toolCallId.equals(approval.getToolCallId())) {
            throw new BizException(409, "审批步骤不属于当前 Agent Run 或 ToolCall");
        }
        if (approval.getStatus() != ApprovalStatus.APPROVED) {
            return null;
        }
        AgentStepEntity step = stepRepository.findById(approval.getStepId())
                .filter(value -> runId.equals(value.getRunId())
                        && toolCallId.equals(value.getToolCallId()))
                .orElseThrow(() -> new BizException(409, "审批步骤不存在或不匹配"));
        return step.getStatus() == com.demetrius.fileagent.agent.domain.step.AgentStepStatus.RUNNING ? step : null;
    }

    private static ApprovalStatus toApprovalStatus(AgentApprovalDecision request) {
        if (request == null || request.decision() == null) {
            throw new BizException("审批决定不能为空");
        }
        return request.decision() == AgentApprovalDecision.Decision.APPROVE
                ? ApprovalStatus.APPROVED : ApprovalStatus.DENIED;
    }

    private static String requireActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw new BizException("审批操作者不能为空");
        }
        return actorId;
    }

    private static void requireCandidate(String runId, AgentActionCandidate candidate) {
        if (runId == null || runId.isBlank() || candidate == null
                || !runId.equals(candidate.runId()) || candidate.toolCallId() == null
                || candidate.toolCallId().isBlank()) {
            throw new IllegalArgumentException("审批动作候选不合法");
        }
    }

    private static String inputAad(AgentActionCandidate candidate) {
        return "agent_approval:" + candidate.runId() + ':' + candidate.toolCallId() + ":input:v1";
    }

    private static String fingerprint(String hmac) {
        if (hmac == null || hmac.isBlank()) {
            return null;
        }
        return hmac.substring(0, Math.min(16, hmac.length()));
    }

    /** 审批决议完成后供接口层安排恢复任务的内部结果，绝不包含原始工具参数。 */
    public record DecisionOutcome(AgentApprovalView approval, AgentRunEntity run, boolean requeued) {
    }

    /** 已完成超时收敛的审批与 Run，用于扫描器在事务提交后发布受控状态事件。 */
    public record ExpirationOutcome(AgentApprovalView approval, AgentRunEntity run) {
    }
}
