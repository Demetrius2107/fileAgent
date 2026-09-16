package com.demetrius.fileagent.api.dto;

import com.demetrius.fileagent.api.enums.AgentRunStatus;

import java.util.List;

/**
 * Agent Run 的受控事件契约（SSE 事件的数据载体）。
 * <p>
 * 只承载事件类型、runId、状态、步骤号、工具名、结果数量、耗时、增量文本、
 * 来源文件、错误码/展示信息和 traceId。工具事件不带文档正文，{@code message.delta}
 * 不带模型推理内容。
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
        String traceId) {

    public static AgentRunEvent runStarted(String runId, String traceId) {
        return new AgentRunEvent("run.started", runId, AgentRunStatus.RUNNING,
                null, null, null, null, null, null, null, null, null, null, traceId);
    }

    public static AgentRunEvent status(String runId, AgentRunStatus status, String summary) {
        return new AgentRunEvent("run.status", runId, status,
                summary, null, null, null, null, null, null, null, null, null, null);
    }

    public static AgentRunEvent toolStarted(String runId, String tool, int step) {
        return new AgentRunEvent("tool.started", runId, null,
                null, step, tool, null, null, null, null, null, null, null, null);
    }

    public static AgentRunEvent toolCompleted(String runId, String tool, int step,
                                              int resultCount, long durationMs) {
        return new AgentRunEvent("tool.completed", runId, null,
                null, step, tool, resultCount, durationMs, null, null, null, null, null, null);
    }

    public static AgentRunEvent delta(String runId, String content) {
        return new AgentRunEvent("message.delta", runId, null,
                null, null, null, null, null, content, null, null, null, null, null);
    }

    public static AgentRunEvent sources(String runId, List<String> files) {
        return new AgentRunEvent("sources", runId, null,
                null, null, null, null, null, null, files, null, null, null, null);
    }

    public static AgentRunEvent completed(String runId, Long messageId) {
        return new AgentRunEvent("run.completed", runId, AgentRunStatus.SUCCEEDED,
                null, null, null, null, null, null, null, null, null, messageId, null);
    }

    public static AgentRunEvent failed(String runId, String code, String message, String traceId) {
        return new AgentRunEvent("run.failed", runId, AgentRunStatus.FAILED,
                null, null, null, null, null, null, null, code, message, null, traceId);
    }
}
