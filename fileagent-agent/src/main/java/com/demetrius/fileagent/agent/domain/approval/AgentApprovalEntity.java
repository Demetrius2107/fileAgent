package com.demetrius.fileagent.agent.domain.approval;

import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import com.demetrius.fileagent.api.enums.ApprovalStatus;
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
 * Agent 操作审批事实：绑定不可变动作快照，客户端只能提交一次决定和受控审计备注。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-12
 */
@Getter
@Entity
@Table(name = "agent_approval", uniqueConstraints = {
        @UniqueConstraint(name = "uk_agent_approval_step", columnNames = "step_id"),
        @UniqueConstraint(name = "uk_agent_approval_run_tool_call", columnNames = {"run_id", "tool_call_id"})
})
public class AgentApprovalEntity {

    private static final int MAX_DECISION_COMMENT_LENGTH = 500;

    /** 对外暴露的审批业务主键，客户端只能据此提交本次决定。 */
    @Id
    @Column(name = "approval_id", nullable = false, updatable = false)
    private String approvalId;

    /** 所属 Run，恢复前据此校验审批仍绑定原始 Agent 执行。 */
    @Column(name = "run_id", nullable = false, updatable = false)
    private String runId;

    /** 被暂停的工具步骤技术主键，一步只允许产生一条审批事实。 */
    @Column(name = "step_id", nullable = false, updatable = false)
    private Long stepId;

    /** AgentScope 为原始工具调用分配的标识，用于恢复时绑定同一个 ToolCall。 */
    @Column(name = "tool_call_id", nullable = false, updatable = false)
    private String toolCallId;

    /** 服务端登记的工具名称，审计和 UI 只展示该受控名称。 */
    @Column(name = "tool_name", nullable = false, updatable = false)
    private String toolName;

    /** 工具实现版本，批准后恢复前必须确认实现没有被替换。 */
    @Column(name = "tool_version", nullable = false, updatable = false)
    private String toolVersion;

    /** 工具参数 Schema 版本，防止批准旧参数契约后按新契约执行。 */
    @Column(name = "tool_definition_version", nullable = false, updatable = false)
    private String toolDefinitionVersion;

    /** 目标端点配置修订号，外部动作恢复前用它检测配置漂移。 */
    @Column(name = "endpoint_revision", nullable = false, updatable = false)
    private String endpointRevision;

    /** 目标端点非敏感配置摘要，禁止将认证信息写入审批事实。 */
    @Column(name = "endpoint_config_hash", nullable = false, updatable = false)
    private String endpointConfigHash;

    /** 作出 ASK 决策时的策略版本，审计时不能用新策略误解释旧决定。 */
    @Column(name = "policy_version", nullable = false, updatable = false)
    private String policyVersion;

    /** 动作风险等级，是 UI 呈现和恢复复核的服务端事实。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, updatable = false)
    private ActionRiskLevel riskLevel;

    /** 已授权的资源范围，模型不能通过审批请求修改该范围。 */
    @Column(name = "resource_scope", nullable = false, updatable = false)
    private String resourceScope;

    /** 规范化原始输入的 HMAC，恢复前用于证明批准的仍是同一参数。 */
    @Column(name = "canonical_input_hmac", nullable = false, updatable = false)
    private String canonicalInputHmac;

    /** 幂等操作标识，外部执行和重试路径据此避免重复产生副作用。 */
    @Column(name = "operation_id", nullable = false, updatable = false)
    private String operationId;

    /** 面向用户的受控动作摘要，不能包含原始密文参数或模型思维链。 */
    @Column(name = "action_summary", nullable = false, updatable = false)
    private String actionSummary;

    /** 面向用户的预期影响摘要，帮助用户在不泄露敏感输入时做决定。 */
    @Column(name = "expected_impact", nullable = false, updatable = false)
    private String expectedImpact;

    /** 脱敏后的参数预览，保存为长文本以支持审计但禁止用于恢复原始输入。 */
    @Lob
    @Column(name = "parameter_preview", nullable = false, updatable = false, columnDefinition = "CLOB")
    private String parameterPreview;

    /** 审批生命周期状态，只有 PENDING 能通过版本条件原子决策。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ApprovalStatus status;

    /** 用户作出决定时的操作者标识，支持后续接入正式认证审计。 */
    @Column(name = "decided_by")
    private String decidedBy;

    /** 用户填写的受控长度、移除控制字符后的审计备注，不注入模型上下文。 */
    @Column(name = "decision_comment", length = MAX_DECISION_COMMENT_LENGTH)
    private String decisionComment;

    /** 最终状态的稳定原因码，接口无需依赖供应商异常文本判断结果。 */
    @Column(name = "resolution_reason")
    private String resolutionReason;

    /** 审批截止时间，到期后只能走 EXPIRED 收敛而不能继续执行旧动作。 */
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** JPA 乐观锁版本，同时也是 CAS 决策的比较条件。 */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** 审批快照创建时间，固定保留用户所批准动作的产生时点。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 最后一次状态决议时间，用于审计和过期扫描后的状态确认。 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentApprovalEntity() {
    }

    private AgentApprovalEntity(String approvalId, String runId, Long stepId, String toolCallId,
                                String toolName, String toolVersion, String toolDefinitionVersion,
                                String endpointRevision, String endpointConfigHash, String policyVersion,
                                ActionRiskLevel riskLevel, String resourceScope, String canonicalInputHmac,
                                String operationId, String actionSummary, String expectedImpact,
                                String parameterPreview, Instant expiresAt, Instant now) {
        this.approvalId = requireText(approvalId, "approvalId");
        this.runId = requireText(runId, "runId");
        this.stepId = requireValue(stepId, "stepId");
        this.toolCallId = requireText(toolCallId, "toolCallId");
        this.toolName = requireText(toolName, "toolName");
        this.toolVersion = requireText(toolVersion, "toolVersion");
        this.toolDefinitionVersion = requireText(toolDefinitionVersion, "toolDefinitionVersion");
        this.endpointRevision = requireText(endpointRevision, "endpointRevision");
        this.endpointConfigHash = requireText(endpointConfigHash, "endpointConfigHash");
        this.policyVersion = requireText(policyVersion, "policyVersion");
        this.riskLevel = requireValue(riskLevel, "riskLevel");
        this.resourceScope = requireText(resourceScope, "resourceScope");
        this.canonicalInputHmac = requireText(canonicalInputHmac, "canonicalInputHmac");
        this.operationId = requireText(operationId, "operationId");
        this.actionSummary = requireText(actionSummary, "actionSummary");
        this.expectedImpact = requireText(expectedImpact, "expectedImpact");
        this.parameterPreview = requireText(parameterPreview, "parameterPreview");
        this.expiresAt = requireValue(expiresAt, "expiresAt");
        this.createdAt = requireValue(now, "now");
        if (!this.expiresAt.isAfter(this.createdAt)) {
            throw new IllegalArgumentException("expiresAt 必须晚于创建时间");
        }
        this.status = ApprovalStatus.PENDING;
        this.updatedAt = this.createdAt;
    }

    public static AgentApprovalEntity pending(String approvalId, String runId, Long stepId, String toolCallId,
                                              String toolName, String toolVersion, String toolDefinitionVersion,
                                              String endpointRevision, String endpointConfigHash, String policyVersion,
                                              ActionRiskLevel riskLevel, String resourceScope,
                                              String canonicalInputHmac, String operationId,
                                              String actionSummary, String expectedImpact, String parameterPreview,
                                              Instant expiresAt, Instant now) {
        return new AgentApprovalEntity(approvalId, runId, stepId, toolCallId, toolName, toolVersion,
                toolDefinitionVersion, endpointRevision, endpointConfigHash, policyVersion, riskLevel,
                resourceScope, canonicalInputHmac, operationId, actionSummary, expectedImpact,
                parameterPreview, expiresAt, now);
    }

    /**
     * 领域路径的批准或拒绝决议；并发入口仍必须配合 JPA 版本条件或乐观锁执行。
     */
    public void decide(ApprovalStatus decision, String actorId, String comment, String reason, Instant now) {
        if (decision != ApprovalStatus.APPROVED && decision != ApprovalStatus.DENIED) {
            throw new IllegalArgumentException("审批决定只能是 APPROVED 或 DENIED");
        }
        requirePending();
        this.status = decision;
        this.decidedBy = requireText(actorId, "actorId");
        this.decisionComment = sanitizeComment(comment);
        this.resolutionReason = requireText(reason, "reason");
        this.updatedAt = requireValue(now, "now");
    }

    /** 审批到期后不能再恢复，需由应用服务同步收敛对应步骤和 Run。 */
    public void expire(String reason, Instant now) {
        requirePending();
        this.status = ApprovalStatus.EXPIRED;
        this.resolutionReason = requireText(reason, "reason");
        this.updatedAt = requireValue(now, "now");
    }

    /** Run 取消或绑定失效时撤销待决审批，避免旧批准重新驱动动作。 */
    public void cancel(String reason, Instant now) {
        requirePending();
        this.status = ApprovalStatus.CANCELLED;
        this.resolutionReason = requireText(reason, "reason");
        this.updatedAt = requireValue(now, "now");
    }

    public static String sanitizeComment(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        String sanitized = comment.replaceAll("[\\p{Cntrl}]", "").strip();
        if (sanitized.length() > MAX_DECISION_COMMENT_LENGTH) {
            return sanitized.substring(0, MAX_DECISION_COMMENT_LENGTH);
        }
        return sanitized.isBlank() ? null : sanitized;
    }

    private void requirePending() {
        if (status != ApprovalStatus.PENDING) {
            throw new IllegalStateException("审批已完成，不能再次决定: " + status);
        }
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
