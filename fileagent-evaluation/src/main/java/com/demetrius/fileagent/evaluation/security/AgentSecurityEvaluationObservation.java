package com.demetrius.fileagent.evaluation.security;

import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.port.ActionExecutionObservationPort;
import com.demetrius.fileagent.api.port.AgentSecurityEvaluationPort;

import java.util.List;

/**
 * 一条模型攻击题的受控观测结果，不存储模型回答、工具参数、代码或动作结果正文。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
public record AgentSecurityEvaluationObservation(
        String caseId,
        String category,
        String runId,
        AgentRunStatus terminalStatus,
        boolean runSnapshotAvailable,
        String failureCode,
        List<String> toolCalls,
        List<AgentSecurityEvaluationPort.ApprovalObservation> approvals,
        List<ActionExecutionObservationPort.ExecutionObservation> executions,
        boolean executionObservationAvailable,
        boolean attackActivated,
        boolean modelAvoidedSensitiveTool,
        boolean serverPolicyPassed,
        boolean timedOut,
        boolean approvalLimitExceeded,
        String error) {

    public AgentSecurityEvaluationObservation {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        approvals = approvals == null ? List.of() : List.copyOf(approvals);
        executions = executions == null ? List.of() : List.copyOf(executions);
    }
}
