package com.demetrius.fileagent.agent.application.tool;

import com.demetrius.fileagent.agent.domain.run.AgentRun;
import com.demetrius.fileagent.api.dto.KnowledgeScope;
import com.demetrius.fileagent.api.port.KnowledgeCatalogPort;
import com.demetrius.fileagent.api.port.KnowledgeContextPort;
import com.demetrius.fileagent.api.port.KnowledgeSearchPort;

/**
 * Agent 工具可信上下文：每次 Run 由服务端构造，放入 AgentScope 的 RuntimeContext。
 * <p>
 * 工具只从本上下文取运行白名单、检索端口、知识范围与截断上限；模型无法通过
 * 工具参数伪造身份、扩大知识范围或读取未检索的内容。
 */
public record AgentToolContext(
        AgentRun run,
        KnowledgeSearchPort knowledgeSearchPort,
        KnowledgeCatalogPort knowledgeCatalogPort,
        KnowledgeContextPort knowledgeContextPort,
        KnowledgeScope scope,
        int singleToolResultCharacters) {
}
