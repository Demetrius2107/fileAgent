package com.demetrius.fileagent.agent.domain.step;

import com.demetrius.fileagent.agent.domain.policy.AgentActionCandidate;
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

    /** 数据库技术主键，不作为跨服务或对外暴露的步骤标识。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属 Agent Run 的业务主键，用于按一次运行聚合全部步骤审计事实。 */
    @Column(name = "run_id", nullable = false, updatable = false)
    private String runId;

    /** Run 内从 1 开始的步骤序号；同一 Run 中不可重复且不可在创建后修改。 */
    @Column(name = "sequence_no", nullable = false, updatable = false)
    private int sequenceNo;

    /** 步骤类别，区分模型调用、工具调用和最终化事务，创建后不可变。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", nullable = false, updatable = false)
    private AgentStepType stepType;

    /** 步骤生命周期状态，控制是否可等待审批、完成、失败或过期。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AgentStepStatus status;

    /** 模型为本次工具调用分配的标识，用于在同一 Run 内去重工具请求。 */
    @Column(name = "tool_call_id")
    private String toolCallId;

    /** 执行的受控工具名称，只记录服务端工具白名单中的稳定名称。 */
    @Column(name = "tool_name")
    private String toolName;

    /** 工具实现版本，供审计时还原当时实际运行的工具逻辑。 */
    @Column(name = "tool_version")
    private String toolVersion;

    /** 工具定义和 JSON Schema 版本，区分实现相同但参数契约不同的调用。 */
    @Column(name = "tool_definition_version")
    private String toolDefinitionVersion;

    /** 外部端点配置修订号，供后续接入外部工具时审计目标配置。 */
    @Column(name = "endpoint_revision")
    private String endpointRevision;

    /** 外部端点非敏感配置的摘要，记录配置变化而不落库认证信息。 */
    @Column(name = "endpoint_config_hash")
    private String endpointConfigHash;

    /** 本步骤操作的风险等级，作为自动执行与人工审批的决策依据。 */
    @Column(name = "risk_level")
    private String riskLevel;

    /** 策略引擎对本步骤给出的决策结果，例如允许、拒绝或要求审批。 */
    @Column(name = "policy_decision")
    private String policyDecision;

    /** 产生 policyDecision 的策略版本，避免审计时用新规则解释旧决策。 */
    @Column(name = "policy_version")
    private String policyVersion;

    /** 可对外展示的安全摘要，禁止包含工具原始参数、文档正文或模型思维链。 */
    @Column(name = "safe_summary")
    private String safeSummary;

    /** 规范化工具输入的 HMAC，用于校验和幂等比对，不能反推出原始参数。 */
    @Column(name = "canonical_input_hmac")
    private String canonicalInputHmac;

    /** 加密后的工具原始输入，仅供受控恢复和审计路径读取，严禁写入日志。 */
    @Lob
    @Column(name = "input_ciphertext", columnDefinition = "CLOB")
    private String inputCiphertext;

    /** 由规范化操作语义生成的幂等指纹，防止恢复后重复执行同一外部操作。 */
    @Column(name = "operation_fingerprint")
    private String operationFingerprint;

    /** 外部系统或本地操作的业务标识，用于关联可查询的操作结果。 */
    @Column(name = "operation_id")
    private String operationId;

    /** 外部供应商返回的执行标识，便于追踪异步或长耗时操作。 */
    @Column(name = "execution_id")
    private String executionId;

    /** 步骤最终结果的稳定代码，供恢复逻辑、接口和评测识别结果类型。 */
    @Column(name = "result_code")
    private String resultCode;

    /** 步骤最终结果的安全摘要，不能包含供应商异常堆栈或敏感原文。 */
    @Column(name = "result_summary")
    private String resultSummary;

    /** 从开始到终态的实际耗时毫秒数；未开始即结束的步骤记为 0。 */
    @Column(name = "duration_ms")
    private Long durationMs;

    /** JPA 乐观锁版本，防止并发恢复或审批处理覆盖步骤状态。 */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** 步骤创建时间；创建后不可修改，用于保留 Run 内的真实审计顺序。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 步骤从 PENDING 进入 RUNNING 的时间；尚未执行时为空。 */
    @Column(name = "started_at")
    private Instant startedAt;

    /** 步骤进入成功、失败、取消或过期终态的时间；非终态时为空。 */
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

    /**
     * 创建已经通过基础校验但必须等待审批的工具步骤，所有可恢复绑定事实一次写入。
     */
    public static AgentStepEntity waitingApproval(AgentActionCandidate candidate, int sequenceNo,
                                                  String inputCiphertext, Instant now) {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate 不能为空");
        }
        AgentStepEntity step = pending(candidate.runId(), sequenceNo, AgentStepType.TOOL_CALL, now);
        step.toolCallId = requireText(candidate.toolCallId(), "toolCallId");
        step.toolName = requireText(candidate.toolName(), "toolName");
        step.toolVersion = requireText(candidate.toolVersion(), "toolVersion");
        step.toolDefinitionVersion = requireText(candidate.toolDefinitionVersion(), "toolDefinitionVersion");
        step.endpointRevision = requireText(candidate.endpointRevision(), "endpointRevision");
        step.endpointConfigHash = requireText(candidate.endpointConfigHash(), "endpointConfigHash");
        step.riskLevel = requireValue(candidate.riskLevel(), "riskLevel").name();
        step.policyDecision = "ASK";
        step.policyVersion = requireText(candidate.policyVersion(), "policyVersion");
        step.safeSummary = requireText(candidate.safeSummary(), "safeSummary");
        step.canonicalInputHmac = requireText(candidate.canonicalInputHmac(), "canonicalInputHmac");
        step.inputCiphertext = requireText(inputCiphertext, "inputCiphertext");
        step.operationFingerprint = requireText(candidate.operationId(), "operationId");
        step.operationId = candidate.operationId();
        step.start(now);
        step.waitForApproval();
        return step;
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

    /**
     * 用户已经对绑定动作作出决定后恢复原步骤，不能通过新建步骤绕过原审批审计。
     */
    public void resumeAfterApproval() {
        requireStatus(AgentStepStatus.WAITING_APPROVAL);
        this.status = AgentStepStatus.RUNNING;
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
