package com.demetrius.fileagent.agent.domain.step;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

/**
 * Agent Run 的步骤审计事实；工具参数只保留密文和安全摘要，不能用它重建模型思维链。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
@Getter
@Entity
@Table(name = "agent_step", uniqueConstraints = {
        @UniqueConstraint(name = "uk_agent_step_run_sequence", columnNames = {"run_id", "sequence_no"}),
        @UniqueConstraint(name = "uk_agent_step_run_tool_call", columnNames = {"run_id", "tool_call_id"}),
        @UniqueConstraint(name = "uk_agent_step_run_operation_fingerprint",
                columnNames = {"run_id", "operation_fingerprint"})
})
public class AgentStepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private String runId;

    @Column(name = "sequence_no", nullable = false, updatable = false)
    private int sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", nullable = false, updatable = false)
    private AgentStepType stepType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AgentStepStatus status;

    @Column(name = "tool_call_id")
    private String toolCallId;

    @Column(name = "tool_name")
    private String toolName;

    @Column(name = "tool_version")
    private String toolVersion;

    @Column(name = "tool_definition_version")
    private String toolDefinitionVersion;

    @Column(name = "endpoint_revision")
    private String endpointRevision;

    @Column(name = "endpoint_config_hash")
    private String endpointConfigHash;

    @Column(name = "risk_level")
    private String riskLevel;

    @Column(name = "policy_decision")
    private String policyDecision;

    @Column(name = "policy_version")
    private String policyVersion;

    @Column(name = "safe_summary")
    private String safeSummary;

    @Column(name = "canonical_input_hmac")
    private String canonicalInputHmac;

    @Lob
    @Column(name = "input_ciphertext", columnDefinition = "CLOB")
    private String inputCiphertext;

    @Column(name = "operation_fingerprint")
    private String operationFingerprint;

    @Column(name = "operation_id")
    private String operationId;

    @Column(name = "execution_id")
    private String executionId;

    @Column(name = "result_code")
    private String resultCode;

    @Column(name = "result_summary")
    private String resultSummary;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    protected AgentStepEntity() {
    }

    private AgentStepEntity(String runId, int sequenceNo, AgentStepType stepType, Instant now) {
        this.runId = requireText(runId, "runId");
        if (sequenceNo < 1) {
            throw new IllegalArgumentException("sequenceNo 必须大于 0");
        }
        this.sequenceNo = sequenceNo;
        this.stepType = requireValue(stepType, "stepType");
        this.status = AgentStepStatus.PENDING;
        this.createdAt = requireValue(now, "now");
    }

    public static AgentStepEntity pending(String runId, int sequenceNo, AgentStepType stepType, Instant now) {
        return new AgentStepEntity(runId, sequenceNo, stepType, now);
    }

    public void start(Instant now) {
        Instant transitionTime = requireValue(now, "now");
        requireStatus(AgentStepStatus.PENDING);
        this.status = AgentStepStatus.RUNNING;
        this.startedAt = transitionTime;
    }

    public void waitForApproval() {
        requireStatus(AgentStepStatus.RUNNING);
        this.status = AgentStepStatus.WAITING_APPROVAL;
    }

    public void succeed(String code, String summary, Instant now) {
        Instant transitionTime = requireValue(now, "now");
        String result = requireText(code, "resultCode");
        requireStatus(AgentStepStatus.RUNNING);
        this.status = AgentStepStatus.SUCCEEDED;
        this.resultCode = result;
        this.resultSummary = summary;
        finish(transitionTime);
    }

    public void fail(String code, Instant now) {
        finishWith(AgentStepStatus.FAILED, code, now,
                AgentStepStatus.PENDING, AgentStepStatus.RUNNING, AgentStepStatus.WAITING_APPROVAL);
    }

    public void cancel(Instant now) {
        finishWith(AgentStepStatus.CANCELLED, null, now,
                AgentStepStatus.PENDING, AgentStepStatus.RUNNING, AgentStepStatus.WAITING_APPROVAL);
    }

    public void expire(Instant now) {
        finishWith(AgentStepStatus.EXPIRED, null, now, AgentStepStatus.WAITING_APPROVAL);
    }

    private void finishWith(AgentStepStatus target, String code, Instant now, AgentStepStatus... expectedStatuses) {
        Instant transitionTime = requireValue(now, "now");
        requireOneOf(expectedStatuses);
        this.status = target;
        this.resultCode = code;
        finish(transitionTime);
    }

    private void finish(Instant now) {
        this.endedAt = now;
        this.durationMs = startedAt == null ? 0L : Math.max(0L, Duration.between(startedAt, now).toMillis());
    }

    private void requireStatus(AgentStepStatus expected) {
        if (status != expected) {
            throw illegalTransition();
        }
    }

    private void requireOneOf(AgentStepStatus... expectedStatuses) {
        if (Arrays.stream(expectedStatuses).noneMatch(expected -> expected == status)) {
            throw illegalTransition();
        }
    }

    private IllegalStateException illegalTransition() {
        return new IllegalStateException("非法步骤状态转移: 当前状态 " + status);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    private static <T> T requireValue(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
