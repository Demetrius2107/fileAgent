package com.demetrius.fileagent.agent.infrastructure.runtime;

import com.demetrius.fileagent.api.dto.AgentRunEvent;
import com.demetrius.fileagent.api.enums.AgentRunStatus;

import java.util.List;

/**
 * Agent 运行事件映射器：把 AgentScope 运行信号映射为受控的 {@link AgentRunEvent}。
 * <p>
 * 只映射公开事件；工具事件只带工具名、结果数量与耗时，绝不带文档正文或模型推理内容。
 */
public class AgentScopeEventMapper {

    public AgentRunEvent started(String runId, String traceId) {
        return AgentRunEvent.runStarted(runId, traceId);
    }

    public AgentRunEvent status(String runId, AgentRunStatus status, String summary) {
        return AgentRunEvent.status(runId, status, summary);
    }

    public AgentRunEvent toolStarted(String runId, String tool, int step) {
        return AgentRunEvent.toolStarted(runId, tool, step);
    }

    public AgentRunEvent toolCompleted(String runId, String tool, int step, int resultCount, long durationMs) {
        return AgentRunEvent.toolCompleted(runId, tool, step, resultCount, durationMs);
    }

    public AgentRunEvent delta(String runId, String content) {
        return AgentRunEvent.delta(runId, content);
    }

    public AgentRunEvent sources(String runId, List<String> files) {
        return AgentRunEvent.sources(runId, files);
    }

    public AgentRunEvent completed(String runId, Long messageId) {
        return AgentRunEvent.completed(runId, messageId);
    }

    public AgentRunEvent failed(String runId, String code, String message, String traceId) {
        return AgentRunEvent.failed(runId, code, message, traceId);
    }
}
