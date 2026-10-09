package com.demetrius.fileagent.agent.domain.run;

import com.demetrius.fileagent.api.enums.AgentRunStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;

import java.time.Instant;
import java.util.Arrays;

/**
 * Agent Run 业务事实：保存唯一合法状态机、预算累计、租约和恢复请求密文。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
@Getter
@Entity
@Table(name = "agent_run", uniqueConstraints =
        @UniqueConstraint(name = "uk_agent_run_active_slot", columnNames = "active_slot"))
public class AgentRunEntity {

    private static final int REQUEST_SCHEMA_VERSION = 1;
    private static final String TIMEOUT_CODE = "AGENT_RUN_TIMEOUT";

    @Id
    @Column(name = "run_id", nullable = false, updatable = false)
    private String runId;

    @Column(name = "session_id", nullable = false, updatable = false)
    private Long sessionId;

    @Column(name = "active_slot")
    private String activeSlot;

    @Column(name = "input_message_id")
    private Long inputMessageId;

    @Column(name = "assistant_message_id")
    private Long assistantMessageId;

    @Column(name = "request_schema_version", nullable = false)
    private int requestSchemaVersion;

    @Lob
    @Column(name = "request_ciphertext", nullable = false, columnDefinition = "CLOB")
    private String requestCiphertext;

    @Column(name = "request_hmac", nullable = false)
    private String requestHmac;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private String actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AgentRunStatus status;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "trace_id", nullable = false, updatable = false)
    private String traceId;

    @Column(name = "step_count", nullable = false)
    private int stepCount;

    @Column(name = "model_call_count", nullable = false)
    private int modelCallCount;

    @Column(name = "history_characters", nullable = false)
    private int historyCharacters;

    @Column(name = "summary_characters", nullable = false)
    private int summaryCharacters;

    @Column(name = "search_snippet_characters", nullable = false)
    private int searchSnippetCharacters;

    @Column(name = "document_read_characters", nullable = false)
    private int documentReadCharacters;

    @Column(name = "tool_result_characters", nullable = false)
    private int toolResultCharacters;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;

    @Column(name = "active_elapsed_ms", nullable = false)
    private long activeElapsedMs;

    @Lob
    @Column(name = "budget_reasons", columnDefinition = "CLOB")
    private String budgetReasons;

    @Column(name = "execution_owner")
    private String executionOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentRunEntity() {
    }

    private AgentRunEntity(String runId, Long sessionId, String traceId, String actorId,
                           String requestCiphertext, String requestHmac, Instant now) {
        this.runId = requireText(runId, "runId");
        this.sessionId = requireValue(sessionId, "sessionId");
        this.traceId = requireText(traceId, "traceId");
        this.actorId = requireText(actorId, "actorId");
        this.requestCiphertext = requireText(requestCiphertext, "requestCiphertext");
        this.requestHmac = requireText(requestHmac, "requestHmac");
        this.requestSchemaVersion = REQUEST_SCHEMA_VERSION;
        this.activeSlot = "session:" + sessionId;
        this.status = AgentRunStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static AgentRunEntity pending(String runId, Long sessionId, String traceId, String actorId,
                                         String requestCiphertext, String requestHmac) {
        return new AgentRunEntity(runId, sessionId, traceId, actorId, requestCiphertext, requestHmac,
                Instant.now());
    }

    public void start(Instant now) {
        requireStatus(AgentRunStatus.PENDING);
        this.status = AgentRunStatus.RUNNING;
        this.startedAt = now;
        touch(now);
    }

    public void succeed(Long messageId, Instant now) {
        requireStatus(AgentRunStatus.RUNNING);
        this.assistantMessageId = requireValue(messageId, "messageId");
        this.status = AgentRunStatus.SUCCEEDED;
        finish(now);
    }

    public void fail(String code, Instant now) {
        requireOneOf(AgentRunStatus.PENDING, AgentRunStatus.RUNNING, AgentRunStatus.WAITING_APPROVAL);
        this.status = AgentRunStatus.FAILED;
        this.failureCode = requireText(code, "failureCode");
        finish(now);
    }

    public void cancel(Instant now) {
        requireOneOf(AgentRunStatus.PENDING, AgentRunStatus.RUNNING, AgentRunStatus.WAITING_APPROVAL);
        this.status = AgentRunStatus.CANCELLED;
        finish(now);
    }

    public void timeout(Instant now) {
        requireOneOf(AgentRunStatus.RUNNING, AgentRunStatus.WAITING_APPROVAL);
        this.status = AgentRunStatus.TIMED_OUT;
        this.failureCode = TIMEOUT_CODE;
        finish(now);
    }

    private void finish(Instant now) {
        this.endedAt = now;
        this.activeSlot = null;
        this.executionOwner = null;
        this.leaseExpiresAt = null;
        touch(now);
    }

    private void touch(Instant now) {
        this.updatedAt = requireValue(now, "now");
    }

    private void requireStatus(AgentRunStatus expected) {
        if (status != expected) {
            throw illegalTransition();
        }
    }

    private void requireOneOf(AgentRunStatus... expectedStatuses) {
        if (Arrays.stream(expectedStatuses).noneMatch(expected -> expected == status)) {
            throw illegalTransition();
        }
    }

    private IllegalStateException illegalTransition() {
        return new IllegalStateException("非法状态转移: 当前状态 " + status);
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
