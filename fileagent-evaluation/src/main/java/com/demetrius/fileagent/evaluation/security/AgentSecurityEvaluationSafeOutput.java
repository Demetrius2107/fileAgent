package com.demetrius.fileagent.evaluation.security;

import com.demetrius.fileagent.api.port.ActionExecutionObservationPort;
import com.demetrius.fileagent.api.port.AgentSecurityEvaluationPort;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 安全攻击评测对外输出的最小白名单清洗器，防止异常文本或运行时字段回显攻击提示词、参数和密钥。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
final class AgentSecurityEvaluationSafeOutput {

    private static final Pattern CASE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern CATEGORY = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern TOOL_NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern VERSION = Pattern.compile("[a-zA-Z0-9]+(?:[._-][a-zA-Z0-9]+)*");
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Set<String> SECURITY_EVALUATION_ERRORS = Set.of(
            "SECURITY_EVALUATION_APPROVAL_LIMIT",
            "SECURITY_EVALUATION_TIMEOUT",
            "SECURITY_EVALUATION_RUNTIME_FAILURE",
            "SECURITY_EVALUATION_CANCEL_FAILED",
            "SECURITY_EVALUATION_PREPARATION_FAILED",
            "SECURITY_EVALUATION_SNAPSHOT_OBSERVATION_FAILED",
            "SECURITY_EVALUATION_LEDGER_OBSERVATION_FAILED",
            "SECURITY_EVALUATION_CASE_FAILED",
            "SECURITY_EVALUATION_SESSION_CLEANUP_FAILED");
    private static final Set<String> RUN_STATUSES = Set.of(
            "PENDING", "RUNNING", "WAITING_USER_INPUT", "WAITING_APPROVAL",
            "SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT");
    private static final Set<String> APPROVAL_STATUSES = Set.of(
            "PENDING", "APPROVED", "DENIED", "EXPIRED", "CANCELLED");
    private static final Set<String> EXECUTION_STATUSES = Set.of(
            "PENDING", "EXECUTING", "SUCCEEDED", "FAILED", "OUTCOME_UNKNOWN", "CANCELLED");

    private AgentSecurityEvaluationSafeOutput() {
    }

    static String caseId(String value) {
        return matches(CASE_ID, value) ? value : "invalid-case";
    }

    static String category(String value) {
        return matches(CATEGORY, value) ? value : "UNCLASSIFIED";
    }

    static String version(String value) {
        return matches(VERSION, value) ? value : "invalid-version";
    }

    static String toolName(String value) {
        return matches(TOOL_NAME, value) ? value : "unrecognized_tool";
    }

    static String securityEvaluationError(String value) {
        return SECURITY_EVALUATION_ERRORS.contains(value) ? value : "SECURITY_EVALUATION_UNCONTROLLED_ERROR";
    }

    static String nullableSecurityEvaluationError(String value) {
        return value == null ? null : securityEvaluationError(value);
    }

    static String nullableRunFailureCode(String value) {
        return value == null ? null : "AGENT_RUN_FAILURE_REDACTED";
    }

    static String nullableExecutionResultCode(String value) {
        return value == null ? null : "ACTION_RESULT_REDACTED";
    }

    static String runStatus(String value) {
        return RUN_STATUSES.contains(value) ? value : "UNAVAILABLE";
    }

    static String approvalStatus(String value) {
        return APPROVAL_STATUSES.contains(value) ? value : "UNAVAILABLE";
    }

    static String executionStatus(String value) {
        return EXECUTION_STATUSES.contains(value) ? value : "UNAVAILABLE";
    }

    static String nullableUuid(String value) {
        return matches(UUID, value) ? value : null;
    }

    static List<String> toolNames(List<String> values) {
        return values == null ? List.of() : values.stream().map(AgentSecurityEvaluationSafeOutput::toolName).toList();
    }

    static String approvalState(String value) {
        String[] parts = value == null ? new String[0] : value.split(":", -1);
        if (parts.length != 2) {
            return "unrecognized_tool:UNAVAILABLE";
        }
        return toolName(parts[0]) + ":" + approvalStatus(parts[1]);
    }

    static String executionState(String value) {
        String[] parts = value == null ? new String[0] : value.split(":", -1);
        if (parts.length != 3) {
            return "unrecognized_tool:UNAVAILABLE:UNAVAILABLE";
        }
        return toolName(parts[0]) + ":" + executionStatus(parts[1]) + ":"
                + nullableExecutionResultCode(parts[2]);
    }

    static List<AgentSecurityEvaluationPort.ApprovalObservation> approvals(
            List<AgentSecurityEvaluationPort.ApprovalObservation> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null)
                .map(value -> new AgentSecurityEvaluationPort.ApprovalObservation(
                        nullableUuid(value.approvalId()), toolName(value.toolName()), value.status()))
                .toList();
    }

    static List<ActionExecutionObservationPort.ExecutionObservation> executions(
            List<ActionExecutionObservationPort.ExecutionObservation> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null)
                .map(value -> new ActionExecutionObservationPort.ExecutionObservation(
                        nullableUuid(value.executionId()), toolName(value.toolName()), value.status(),
                        nullableExecutionResultCode(value.resultCode())))
                .toList();
    }

    private static boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }
}
