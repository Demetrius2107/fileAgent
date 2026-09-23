package com.demetrius.fileagent.api.dto;

import java.util.List;

/**
 * Agent Run 执行命令（服务端内部构造的可信契约）。
 * <p>
 * 浏览器只提交 prompt；sessionId 来自路径；traceId 由追踪上下文读取；
 * KnowledgeScope 由服务端固定。历史消息仅作文本上下文，不作为工具权限来源。
 */
public record AgentRunCommand(
        String runId,
        Long sessionId,
        String traceId,
        String prompt,
        String historySummary,
        Long historySummaryThroughMessageId,
        long historySummaryVersion,
        String historySummarySourceHash,
        List<MessageDto> history,
        KnowledgeScope knowledgeScope) {

    public AgentRunCommand(
            String runId,
            Long sessionId,
            String traceId,
            String prompt,
            List<MessageDto> history,
            KnowledgeScope knowledgeScope) {
        this(runId, sessionId, traceId, prompt, null, null, 0L, null, history, knowledgeScope);
    }
}
