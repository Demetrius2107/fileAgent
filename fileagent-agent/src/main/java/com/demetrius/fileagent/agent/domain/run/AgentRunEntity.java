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

    /** 持久化请求载荷的当前编码版本，恢复时据此拒绝不兼容的旧数据。 */
    private static final int REQUEST_SCHEMA_VERSION = 1;
    /** 超时终态统一使用的稳定错误码，供接口和评测识别。 */
    private static final String TIMEOUT_CODE = "AGENT_RUN_TIMEOUT";

    /** Run 的全局唯一标识，由服务端创建并作为所有关联记录的业务主键。 */
    @Id
    @Column(name = "run_id", nullable = false, updatable = false)
    private String runId;

    /** Run 所属会话；同一会话首版只允许一个非终态 Run。 */
    @Column(name = "session_id", nullable = false, updatable = false)
    private Long sessionId;

    /**
     * 活动 Run 对会话的唯一占位，值固定为 {@code session:{sessionId}}。
     * 终态必须清空，数据库唯一约束才允许会话创建下一次 Run。
     */
    @Column(name = "active_slot")
    private String activeSlot;

    /** 创建 Run 时同步落库的用户消息 ID，用于恢复时追溯本次请求来源。 */
    @Column(name = "input_message_id")
    private Long inputMessageId;

    /** 最终化事务成功后写入的助手消息 ID；未成功前保持为空，避免重复落库。 */
    @Column(name = "assistant_message_id")
    private Long assistantMessageId;

    /** requestCiphertext 的载荷格式版本，用于恢复前的兼容性校验。 */
    @Column(name = "request_schema_version", nullable = false)
    private int requestSchemaVersion;

    /**
     * 加密后的可信 AgentRunCommand；PENDING Run 在入队前崩溃时依靠它重新入队。
     * 请求明文不能写入数据库或日志。
     */
    @Lob
    @Column(name = "request_ciphertext", nullable = false, columnDefinition = "CLOB")
    private String requestCiphertext;

    /** 规范化请求的 HMAC，恢复前用于确认密文所代表的业务输入未被替换。 */
    @Column(name = "request_hmac", nullable = false)
    private String requestHmac;

    /** 当前本地操作者标识；后续接入认证后用于审计 Run 的发起主体。 */
    @Column(name = "actor_id", nullable = false, updatable = false)
    private String actorId;

    /** Run 生命周期状态；只能通过聚合方法迁移，禁止外部直接修改。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AgentRunStatus status;

    /** 失败或超时后的稳定原因码，不保存供应商异常正文。 */
    @Column(name = "failure_code")
    private String failureCode;

    /** 贯穿 HTTP、后台执行和日志的追踪标识，用于关联一次 Run 的诊断记录。 */
    @Column(name = "trace_id", nullable = false, updatable = false)
    private String traceId;

    /** 已消耗的 Agent 步骤数，用于执行预算和事后审计。 */
    @Column(name = "step_count", nullable = false)
    private int stepCount;

    /** 已发起的模型调用数，用于限制 ReAct 循环。 */
    @Column(name = "model_call_count", nullable = false)
    private int modelCallCount;

    /** 本次 Run 实际保留的历史消息字符数。 */
    @Column(name = "history_characters", nullable = false)
    private int historyCharacters;

    /** 注入模型的历史摘要字符数，和原始历史字符数分开统计。 */
    @Column(name = "summary_characters", nullable = false)
    private int summaryCharacters;

    /** 工具检索结果注入模型的摘要字符累计值。 */
    @Column(name = "search_snippet_characters", nullable = false)
    private int searchSnippetCharacters;

    /** 读取文档上下文工具返回并注入模型的字符累计值。 */
    @Column(name = "document_read_characters", nullable = false)
    private int documentReadCharacters;

    /** 所有工具结果注入模型的字符累计值，用于防止工具输出撑爆上下文。 */
    @Column(name = "tool_result_characters", nullable = false)
    private int toolResultCharacters;

    /** 模型调用上报的输入 Token 累计值；模型未上报时保持 0。 */
    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    /** 模型调用上报的输出 Token 累计值；模型未上报时保持 0。 */
    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    /** 模型调用上报或由输入、输出推导的总 Token 累计值。 */
    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;

    /** 实际占用执行能力的累计毫秒数，不包含等待审批时间。 */
    @Column(name = "active_elapsed_ms", nullable = false)
    private long activeElapsedMs;

    /** 命中的预算原因集合的持久化文本，用于快照和失败诊断。 */
    @Lob
    @Column(name = "budget_reasons", columnDefinition = "CLOB")
    private String budgetReasons;

    /** 当前持有执行租约的实例标识，防止多个恢复协调器同时执行同一 Run。 */
    @Column(name = "execution_owner")
    private String executionOwner;

    /** 执行租约失效时间；到期后才允许其他实例按恢复矩阵接管。 */
    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    /** JPA 乐观锁版本，保护 Run 状态和租约的 CAS 更新。 */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** Run 创建时间；创建后不可修改。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 首次从 PENDING 进入 RUNNING 的时间；排队 Run 保持为空。 */
    @Column(name = "started_at")
    private Instant startedAt;

    /** 进入终态的时间；非终态 Run 保持为空。 */
    @Column(name = "ended_at")
    private Instant endedAt;

    /** 最近一次状态、租约或预算事实变更的时间，用于恢复扫描和审计。 */
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

    /**
     * 绑定与本次请求同事务创建的用户消息，恢复时可据此审计 PENDING Run 的输入来源。
     */
    public void recordInputMessage(Long messageId, Instant now) {
        requireStatus(AgentRunStatus.PENDING);
        this.inputMessageId = requireValue(messageId, "messageId");
        touch(now);
    }

    public void start(Instant now) {
        requireStatus(AgentRunStatus.PENDING);
        this.status = AgentRunStatus.RUNNING;
        if (this.startedAt == null) {
            this.startedAt = now;
        }
        touch(now);
    }

    /**
     * 在 AgentScope 已保存 ASKING 检查点后暂停 Run；保留 active slot 以阻止同会话并发新 Run。
     */
    public void waitForApproval(Instant now) {
        requireStatus(AgentRunStatus.RUNNING);
        this.status = AgentRunStatus.WAITING_APPROVAL;
        this.executionOwner = null;
        this.leaseExpiresAt = null;
        touch(now);
    }

    /**
     * 审批结论已持久化后重新进入可调度队列，实际开始仍由协调器持有租约后完成。
     */
    public void resumeAfterApproval(Instant now) {
        requireStatus(AgentRunStatus.WAITING_APPROVAL);
        this.status = AgentRunStatus.PENDING;
        this.executionOwner = null;
        this.leaseExpiresAt = null;
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
