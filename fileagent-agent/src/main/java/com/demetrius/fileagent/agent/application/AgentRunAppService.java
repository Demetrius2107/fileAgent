package com.demetrius.fileagent.agent.application;

import com.demetrius.fileagent.agent.interfaces.dto.StartAgentRunRequest;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import reactor.core.publisher.Flux;

/**
 * Agent 运行编排：校验会话、加载历史、保存用户消息、启动 Runtime、落库助手回答。
 */
public interface AgentRunAppService {

    Flux<AgentRunEvent> run(Long sessionId, StartAgentRunRequest request, String traceId);
}
