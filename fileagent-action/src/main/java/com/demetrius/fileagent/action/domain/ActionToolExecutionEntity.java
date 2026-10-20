package com.demetrius.fileagent.action.domain;

import com.demetrius.fileagent.api.dto.action.ActionExecutionCommand;
import com.demetrius.fileagent.api.enums.ActionExecutionStatus;
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

/**
 * 外部副作用执行账本：用服务端生成的三类标识去重，并保存可恢复的确定执行结果。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-20
 */
@Getter
@Entity
@Table(name = "action_tool_execution", uniqueConstraints = {
        @UniqueConstraint(name = "uk_action_execution_key", columnNames = "execution_key"),
        @UniqueConstraint(name = "uk_action_operation_id", columnNames = "operation_id"),
        @UniqueConstraint(name = "uk_action_fingerprint", columnNames = {"run_id", "operation_fingerprint"})
})
public class ActionToolExecutionEntity {

    /** 执行账本业务主键，用于查询结果和人工核对未知结果。 */
    @Id
    @Column(name = "execution_id", nullable = false, updatable = false)
    private String executionId;

    /** 同一个持久化 ToolCall 的稳定 HMAC，重放时必须命中原执行记录。 */
    @Column(name = "execution_key", nullable = false, updatable = false)
    private String executionKey;

    /** 同一 Run 内动作语义的稳定 HMAC，阻止模型用新 ToolCall 重复副作用。 */
    @Column(name = "operation_fingerprint", nullable = false, updatable = false)
    private String operationFingerprint;

    /** 服务端生成的业务流水号，同时作为远端接口的幂等值。 */
    @Column(name = "operation_id", nullable = false, updatable = false)
    private String operationId;

    /** 所属 Agent Run，用于限定语义去重范围和恢复执行上下文。 */
    @Column(name = "run_id", nullable = false, updatable = false)
    private String runId;

    /** AgentScope 原始工具调用标识，用于把账本事实绑定到具体步骤。 */
    @Column(name = "tool_call_id", nullable = false, updatable = false)
    private String toolCallId;

    /** 服务端登记的工具名称，用于审计执行入口而不信任模型自报身份。 */
    @Column(name = "tool_name", nullable = false, updatable = false)
    private String toolName;

    /** 工具实现版本，恢复时用于识别执行语义是否发生变化。 */
    @Column(name = "tool_version", nullable = false, updatable = false)
    private String toolVersion;

    /** 动作定义版本，防止旧参数按新契约重复执行。 */
    @Column(name = "tool_definition_version", nullable = false, updatable = false)
    private String toolDefinitionVersion;

    /** 注册 Endpoint 修订号，保证账本结果对应当时批准的远端目标。 */
    @Column(name = "endpoint_revision", nullable = false, updatable = false)
    private String endpointRevision;

    /** 执行生命周期状态，结果未知时必须停留在 OUTCOME_UNKNOWN 并禁止自动重试。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ActionExecutionStatus status;

    /** 实际开始执行的次数；CAS 抢占成功后递增，用于审计重复调度。 */
    @Column(name = "attempt", nullable = false)
    private int attempt;

    /** 规范化请求的服务端 HMAC，用于验证恢复前后的输入没有变化。 */
    @Column(name = "canonical_request_hmac", nullable = false, updatable = false)
    private String canonicalRequestHmac;

    /** 远端返回的非敏感请求标识，供人工排查未知结果时关联外部系统。 */
    @Column(name = "external_request_id")
    private String externalRequestId;

    /** 稳定结果码，调用方不依赖供应商异常文本判断执行结果。 */
    @Column(name = "result_code")
    private String resultCode;

    /** 受限执行结果的信封密文，崩溃恢复时复用结果而不重复调用远端。 */
    @Lob
    @Column(name = "result_ciphertext", columnDefinition = "CLOB")
    private String resultCiphertext;

    /** 执行结果的服务端 HMAC，用于解密前检测账本内容是否被替换。 */
    @Column(name = "result_hash")
    private String resultHash;

    /** JPA 乐观锁版本，同时也是 PENDING 原子抢占的比较条件。 */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** 首次成功抢占并准备发送远端请求的时间。 */
    @Column(name = "started_at")
    private Instant startedAt;

    /** 执行进入确定终态或未知结果态的时间。 */
    @Column(name = "ended_at")
    private Instant endedAt;

    /** 账本最后更新时间，用于恢复扫描和运维审计。 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ActionToolExecutionEntity() {
    }

    private ActionToolExecutionEntity(String executionId, ActionExecutionCommand command, Instant now) {
        this.executionId = requireText(executionId, "executionId");
        ActionExecutionCommand requiredCommand = requireValue(command, "command");
        this.executionKey = requireText(requiredCommand.executionKey(), "executionKey");
        this.operationFingerprint = requireText(requiredCommand.operationFingerprint(), "operationFingerprint");
        this.operationId = requireText(requiredCommand.operationId(), "operationId");
        this.runId = requireText(requiredCommand.runId(), "runId");
        this.toolCallId = requireText(requiredCommand.toolCallId(), "toolCallId");
        this.toolName = requireText(requiredCommand.toolName(), "toolName");
        this.toolVersion = requireText(requiredCommand.toolVersion(), "toolVersion");
        this.toolDefinitionVersion = requireText(requiredCommand.definitionVersion(), "definitionVersion");
        this.endpointRevision = requireText(requiredCommand.endpointRevision(), "endpointRevision");
        this.canonicalRequestHmac = requireText(requiredCommand.canonicalInputHmac(), "canonicalInputHmac");
        this.status = ActionExecutionStatus.PENDING;
        this.attempt = 0;
        this.updatedAt = requireValue(now, "now");
    }

    public static ActionToolExecutionEntity pending(String executionId,
                                                    ActionExecutionCommand command,
                                                    Instant now) {
        return new ActionToolExecutionEntity(executionId, command, now);
    }

    /** 记录已确认成功的密文结果，使重放路径可以直接复用而不再次产生副作用。 */
    public void markSucceeded(String resultCode, String resultCiphertext, String resultHash,
                              String externalRequestId, Instant now) {
        requireExecuting();
        this.status = ActionExecutionStatus.SUCCEEDED;
        this.resultCode = requireText(resultCode, "resultCode");
        this.resultCiphertext = requireText(resultCiphertext, "resultCiphertext");
        this.resultHash = requireText(resultHash, "resultHash");
        this.externalRequestId = optionalText(externalRequestId);
        finish(now);
    }

    /** 记录远端明确返回的失败；只有这种确定失败才允许上层按策略重新发起新操作。 */
    public void markFailed(String resultCode, String resultCiphertext, String resultHash,
                           String externalRequestId, Instant now) {
        requireExecuting();
        this.status = ActionExecutionStatus.FAILED;
        this.resultCode = requireText(resultCode, "resultCode");
        this.resultCiphertext = optionalText(resultCiphertext);
        this.resultHash = optionalText(resultHash);
        this.externalRequestId = optionalText(externalRequestId);
        finish(now);
    }

    /** 请求可能已经到达远端时保守落为未知结果，任何恢复或调度都不得自动重试。 */
    public void markOutcomeUnknown(String resultCode, Instant now) {
        requireExecuting();
        this.status = ActionExecutionStatus.OUTCOME_UNKNOWN;
        this.resultCode = requireText(resultCode, "resultCode");
        finish(now);
    }

    /** 仅在能够证明请求尚未产生副作用时记录取消。 */
    public void markCancelled(String resultCode, Instant now) {
        if (status != ActionExecutionStatus.PENDING && status != ActionExecutionStatus.EXECUTING) {
            throw new IllegalStateException("只有待执行或执行中的动作可以取消: " + status);
        }
        this.status = ActionExecutionStatus.CANCELLED;
        this.resultCode = requireText(resultCode, "resultCode");
        finish(now);
    }

    private void requireExecuting() {
        if (status != ActionExecutionStatus.EXECUTING) {
            throw new IllegalStateException("只有执行中的动作可以记录结果: " + status);
        }
    }

    private void finish(Instant now) {
        this.endedAt = requireValue(now, "now");
        this.updatedAt = this.endedAt;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static <T> T requireValue(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }
}
