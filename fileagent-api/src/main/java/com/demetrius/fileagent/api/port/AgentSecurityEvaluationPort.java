package com.demetrius.fileagent.api.port;

import com.demetrius.fileagent.api.dto.KnowledgeScope;
import com.demetrius.fileagent.api.enums.AgentRunStatus;
import com.demetrius.fileagent.api.enums.ApprovalStatus;

import java.time.Duration;
import java.util.List;

/**
 * 真实 Agent 安全攻击评测端口：复用持久化 Run、审批和动作账本，不使用回答质量 Judge。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
public interface AgentSecurityEvaluationPort {

    /**
     * 执行单条安全攻击用例，返回服务端可观察的审批、工具和执行账本事实。
     *
     * @param query 安全评测输入
     * @return 受控运行观察，不包含模型思维链、工具参数或动作结果正文
     */
    Result evaluate(Query query);

    /** 安全评测输入。每个 case 必须使用一个独立会话。 */
    record Query(
            Long sessionId,
            String caseId,
            String prompt,
            KnowledgeScope knowledgeScope,
            Duration timeout,
            int maxApprovals) {

        public Query {
            knowledgeScope = knowledgeScope == null ? KnowledgeScope.global() : knowledgeScope;
            timeout = timeout == null ? Duration.ofSeconds(45) : timeout;
            maxApprovals = maxApprovals <= 0 ? 2 : maxApprovals;
        }
    }

    /** 单条审批的受控观察。 */
    record ApprovalObservation(
            String approvalId,
            String toolName,
            ApprovalStatus status) {
    }

    /**
     * 单条安全评测的服务端事实。
     *
     * @param runId                 已创建 Run 的标识；准备阶段失败时为空
     * @param terminalStatus        运行最终快照状态
     * @param runSnapshotAvailable  是否成功读取最终 Run 快照；失败时安全评测必须失败关闭
     * @param failureCode           受控失败码
     * @param eventTypes            事件类型序列，不含消息正文
     * @param toolCalls             工具启动名称序列
     * @param approvals             审批状态序列
     * @param executions            动作账本执行事实
     * @param executionObservationAvailable 动作账本是否成功完成只读观察；失败时安全评测必须失败关闭
     * @param timedOut              评测等待是否超时
     * @param approvalLimitExceeded 是否出现超过 Runner 上限的重复审批请求
     * @param error                 受控评测错误说明
     */
    record Result(
            String runId,
            AgentRunStatus terminalStatus,
            boolean runSnapshotAvailable,
            String failureCode,
            List<String> eventTypes,
            List<String> toolCalls,
            List<ApprovalObservation> approvals,
            List<ActionExecutionObservationPort.ExecutionObservation> executions,
            boolean executionObservationAvailable,
            boolean timedOut,
            boolean approvalLimitExceeded,
            String error) {
        public Result {
            eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            approvals = approvals == null ? List.of() : List.copyOf(approvals);
            executions = executions == null ? List.of() : List.copyOf(executions);
        }
    }
}
