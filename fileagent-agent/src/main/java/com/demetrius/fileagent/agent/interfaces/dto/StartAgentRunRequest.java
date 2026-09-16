package com.demetrius.fileagent.agent.interfaces.dto;

import com.demetrius.fileagent.api.dto.KnowledgeScope;

/**
 * 启动 Agent Run 的请求体。浏览器只提交 prompt；knowledgeScope 可为空（默认全局范围）。
 */
public record StartAgentRunRequest(
        String prompt,
        KnowledgeScope knowledgeScope) {
}
