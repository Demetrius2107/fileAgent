package com.demetrius.fileagent.api.dto;

import com.demetrius.fileagent.api.enums.AgentRunStatus;

import java.util.List;

/**
 * Agent Run 的受控事件契约（SSE 事件的数据载体）。
 * <p>
 * 只承载事件类型、runId、状态、步骤号、工具名、结果数量、耗时、增量文本、
 * 来源文件、结构化引用、错误码/展示信息和 traceId。工具事件不带文档正文，
 * {@code message.delta} 不带模型推理内容。{@code citations} 为 Phase 2C 追加字段，
 * 仅 {@code sources} 事件携带，其余事件保持 null。
 */
public record AgentRunEvent(
        String type,
        String runId,
        AgentRunStatus status,
        String summary,
        Integer step,
        String tool,
        Integer resultCount,
        Long durationMs,
        String content,
        List<String> files,
        String code,
        String message,
        Long messageId,
        String traceId,
        String eventId,
        Long runVersion,
        AgentApprovalView approval,
        List<AgentCitation> citations) {

    public static AgentRunEvent runStarted(String runId, String traceId) {
        return new AgentRunEvent("run.started", runId, AgentRunStatus.RUNNING,
                null, null, null, null, null, null, null, null, null, null, traceId, null, null, null, null);
    }

    public static AgentRunEvent pending(String runId) {
        return status(runId, AgentRunStatus.PENDING, null);
    }

    public static AgentRunEvent status(String runId, AgentRunStatus status, String summary) {
        return new AgentRunEvent("run.status", runId, status,
                summary, null, null, null, null, null, null, null, null, null, null, null, null, null);
        return new AgentRunEvent(
                "run.status",
                runId,
                status,
                summary,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static AgentRunEvent toolStarted(String runId, String tool, int step) {
        return new AgentRunEvent("tool.started", runId, null,
                null, step, tool,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    public static AgentRunEvent toolCompleted(String runId, String tool, int step,
                                              int resultCount, long durationMs) {
        return new AgentRunEvent("tool.completed", runId, null,
                null, step, tool, resultCount, durationMs,
                null, null, null, null, null, null, null, null, null);
    }

    public static AgentRunEvent delta(String runId, String content) {
        return new AgentRunEvent("message.delta", runId, null,
                null, null, null, null, null, content, null, null, null, null, null, null, null, null);
    }

    public static AgentRunEvent sources(String runId, List<String> files) {
        return sources(runId, files, null);
    }

    /** Phase 2C：sources 事件同时携带结构化引用与核验结论。 */
    public static AgentRunEvent sources(String runId, List<String> files, List<AgentCitation> citations) {
        return new AgentRunEvent("sources", runId, null,
                null, null, null, null, null, null, files, null, null, null, null, null, null, citations);
                null, null, null, null, null, null, files, null, null, null, null, null, null, null);
    }

    public static AgentRunEvent completed(String runId, Long messageId) {
        return new AgentRunEvent("run.completed", runId, AgentRunStatus.SUCCEEDED,
                null, null, null, null, null, null, null, null, null,
                messageId, null, null, null, null);
    }

    public static AgentRunEvent failed(String runId, String code, String message, String traceId) {
        return new AgentRunEvent("run.failed", runId, AgentRunStatus.FAILED,
                null, null, null, null, null, null, null, code, message, null, traceId, null, null, null);
    }

    /** SSE 尚未建立前的业务错误事件，不对应任何已创建的 Run。 */
    public static AgentRunEvent error(String code, String message, String traceId) {
        return new AgentRunEvent("error", null, null,
                null, null, null, null, null, null, null, code, message, null, traceId, null, null, null);
    }

    /** AgentScope 已保存 ASKING 检查点后，向客户端公开当前一次不可变动作审批。 */
    public static AgentRunEvent approvalRequired(String runId, AgentApprovalView approval) {
        return new AgentRunEvent("approval.required", runId, AgentRunStatus.WAITING_APPROVAL,
                null, null, approval == null ? null : approval.tool(), null, null,
                null, null, null, null, null, null, null, null, approval);
    }

    /** 审批已落库并重新进入待调度队列；事件不包含用户备注或原始工具参数。 */
    public static AgentRunEvent approvalResolved(String runId, AgentApprovalView approval) {
        return new AgentRunEvent("approval.resolved", runId, AgentRunStatus.PENDING,
                null, null, approval == null ? null : approval.tool(), null, null,
                null, null, null, null, null, null, null, null, approval);
                null, null, null, null, null, null, null, code, message, null, traceId, null, null, null);
    }

    /**
     * 由运行事件通道在发布时写入不可预测事件 ID 与对应 Run 版本。
     */
    public AgentRunEvent withMetadata(String eventId, long runVersion) {
        return new AgentRunEvent(type, runId, status, summary, step, tool, resultCount, durationMs,
                content, files, code, message, messageId, traceId, eventId, runVersion, citations, approval);
    }
}
