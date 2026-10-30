package com.demetrius.fileagent.agent.application;

import com.demetrius.fileagent.agent.interfaces.dto.StartAgentRunRequest;
import com.demetrius.fileagent.api.dto.AgentApprovalDecision;
import com.demetrius.fileagent.api.dto.AgentApprovalView;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import com.demetrius.fileagent.api.dto.AgentRunSnapshot;
import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.port.ActionExecutionObservationPort;
import com.demetrius.fileagent.api.port.AgentRuntimePort;
import com.demetrius.fileagent.api.port.AgentSecurityEvaluationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 真实 Agent 安全评测执行器：复用生产 Run/审批/账本链路，但只收集受控的服务端安全事实。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "fileagent.evaluation.endpoint", name = "enabled", havingValue = "true")
public class AgentSecurityEvaluationService implements AgentSecurityEvaluationPort {

    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(2);
    private static final int MAX_APPROVALS = 5;

    private final AgentRunAppService agentRunAppService;
    private final AgentApprovalWorkflowService approvalWorkflowService;
    private final AgentRuntimePort agentRuntimePort;
    private final ActionExecutionObservationPort actionExecutionObservationPort;

    @Override
    public Result evaluate(Query query) {
        validate(query);
        ObservationCollector collector = new ObservationCollector(query.maxApprovals());
        String runId = null;
        SnapshotObservation snapshotObservation = SnapshotObservation.unavailable();
        try {
            AgentRunPreparation preparation = agentRunAppService.prepare(query.sessionId(),
                    new StartAgentRunRequest(query.prompt().strip(), query.knowledgeScope()),
                    "phase3-security-" + query.caseId());
            runId = preparation.runId();
            collector.setRunId(runId);

            try {
                preparation.events()
                        .doOnNext(event -> collector.record(event, approvalWorkflowService))
                        .takeUntil(AgentSecurityEvaluationService::isTerminalEvent)
                        .blockLast(query.timeout());
            } catch (ApprovalLimitExceededException ignored) {
                collector.markApprovalLimitExceeded();
                collector.markError("SECURITY_EVALUATION_APPROVAL_LIMIT");
            } catch (IllegalStateException exception) {
                if (isBlockingTimeout(exception)) {
                    collector.markTimedOut();
                    collector.markError("SECURITY_EVALUATION_TIMEOUT");
                } else {
                    collector.markError("SECURITY_EVALUATION_RUNTIME_FAILURE");
                }
            } catch (RuntimeException ignored) {
                collector.markError("SECURITY_EVALUATION_RUNTIME_FAILURE");
            }

            snapshotObservation = observeSnapshot(runId);
            if (!snapshotObservation.available() || snapshotObservation.snapshot() == null
                    || !isTerminal(snapshotObservation.snapshot().status())) {
                if (!safelyCancel(runId)) {
                    collector.markError("SECURITY_EVALUATION_CANCEL_FAILED");
                }
                snapshotObservation = observeSnapshot(runId);
            }
        } catch (RuntimeException ignored) {
            collector.markError("SECURITY_EVALUATION_PREPARATION_FAILED");
            if (runId != null) {
                if (!safelyCancel(runId)) {
                    collector.markError("SECURITY_EVALUATION_CANCEL_FAILED");
                }
                snapshotObservation = observeSnapshot(runId);
            }
        }
        if (!snapshotObservation.available()) {
            collector.markError("SECURITY_EVALUATION_SNAPSHOT_OBSERVATION_FAILED");
        }
        ExecutionObservation executionObservation = observeExecutions(runId);
        if (!executionObservation.available()) {
            collector.markError("SECURITY_EVALUATION_LEDGER_OBSERVATION_FAILED");
        }
        return collector.toResult(snapshotObservation, executionObservation);
    }

    private ExecutionObservation observeExecutions(String runId) {
        if (runId == null) {
            return new ExecutionObservation(List.of(), true);
        }
        try {
            return new ExecutionObservation(actionExecutionObservationPort.findByRunId(runId), true);
        } catch (RuntimeException ignored) {
            return new ExecutionObservation(List.of(), false);
        }
    }

    private SnapshotObservation observeSnapshot(String runId) {
        if (runId == null) {
            return SnapshotObservation.unavailable();
        }
        try {
            AgentRunSnapshot snapshot = agentRuntimePort.snapshot(runId);
            return snapshot == null ? SnapshotObservation.unavailable() : new SnapshotObservation(snapshot, true);
        } catch (RuntimeException ignored) {
            return SnapshotObservation.unavailable();
        }
    }

    private boolean safelyCancel(String runId) {
        try {
            agentRuntimePort.cancel(runId);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void validate(Query query) {
        if (query == null || query.sessionId() == null || query.caseId() == null || query.caseId().isBlank()
                || query.prompt() == null || query.prompt().isBlank()) {
            throw new IllegalArgumentException("安全评测请求不完整");
        }
        if (query.timeout().isNegative() || query.timeout().isZero() || query.timeout().compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("安全评测 timeout 必须在 0 到 120 秒之间");
        }
        if (query.maxApprovals() > MAX_APPROVALS) {
            throw new IllegalArgumentException("安全评测最多允许观察 " + MAX_APPROVALS + " 次审批");
        }
    }

    private static boolean isTerminalEvent(AgentRunEvent event) {
        return event != null && isTerminal(event.status());
    }

    private static boolean isTerminal(AgentRunStatus status) {
        return status == AgentRunStatus.SUCCEEDED || status == AgentRunStatus.FAILED
                || status == AgentRunStatus.CANCELLED || status == AgentRunStatus.TIMED_OUT;
    }

    private static boolean isBlockingTimeout(IllegalStateException exception) {
        String message = exception.getMessage();
        return message != null && message.startsWith("Timeout on blocking read");
    }

    private static final class ObservationCollector {

        private final int maxApprovals;
        private final List<String> eventTypes = new ArrayList<>();
        private final List<String> toolCalls = new ArrayList<>();
        private final Map<String, ApprovalObservation> approvals = new LinkedHashMap<>();
        private final Set<String> decidedApprovalIds = new LinkedHashSet<>();
        private String runId;
        private boolean timedOut;
        private boolean approvalLimitExceeded;
        private String error;

        private ObservationCollector(int maxApprovals) {
            this.maxApprovals = maxApprovals;
        }

        private void setRunId(String runId) {
            this.runId = runId;
        }

        private void record(AgentRunEvent event, AgentApprovalWorkflowService approvalWorkflowService) {
            if (event == null) {
                return;
            }
            if (event.type() != null) {
                eventTypes.add(event.type());
            }
            if ("tool.started".equals(event.type()) && event.tool() != null && !event.tool().isBlank()) {
                toolCalls.add(event.tool());
            }
            if (!"approval.required".equals(event.type()) || event.approval() == null) {
                return;
            }
            AgentApprovalView approval = event.approval();
            if (!decidedApprovalIds.add(approval.approvalId())) {
                return;
            }
            approvals.put(approval.approvalId(), new ApprovalObservation(
                    approval.approvalId(), approval.tool(), approval.status()));
            if (decidedApprovalIds.size() > maxApprovals) {
                throw new ApprovalLimitExceededException();
            }
            AgentApprovalView resolved = approvalWorkflowService.decide(runId, approval.approvalId(),
                    new AgentApprovalDecision(AgentApprovalDecision.Decision.DENY,
                            "phase3-security-evaluation"));
            approvals.put(resolved.approvalId(), new ApprovalObservation(
                    resolved.approvalId(), resolved.tool(), resolved.status()));
        }

        private void markTimedOut() {
            timedOut = true;
        }

        private void markApprovalLimitExceeded() {
            approvalLimitExceeded = true;
        }

        private void markError(String value) {
            if (error == null) {
                error = value;
            }
        }

        private Result toResult(SnapshotObservation snapshotObservation, ExecutionObservation executionObservation) {
            AgentRunSnapshot snapshot = snapshotObservation.snapshot();
            return new Result(runId, snapshot == null ? null : snapshot.status(), snapshotObservation.available(),
                    snapshot == null ? null : snapshot.failureCode(), eventTypes, toolCalls,
                    new ArrayList<>(approvals.values()), executionObservation.executions(),
                    executionObservation.available(), timedOut, approvalLimitExceeded, error);
        }
    }

    /** 动作账本观察失败必须和“账本为空”区分，避免安全评测错误放行。 */
    private record ExecutionObservation(
            List<ActionExecutionObservationPort.ExecutionObservation> executions,
            boolean available) {
        private ExecutionObservation {
            executions = executions == null ? List.of() : List.copyOf(executions);
        }
    }

    /** 最终 Run 快照是否可读必须显式建模，不能把读取失败伪装成空快照。 */
    private record SnapshotObservation(AgentRunSnapshot snapshot, boolean available) {
        private static SnapshotObservation unavailable() {
            return new SnapshotObservation(null, false);
        }
    }

    private static final class ApprovalLimitExceededException extends RuntimeException {
    }
}
