package com.demetrius.fileagent.agent.application;

import com.demetrius.fileagent.agent.interfaces.dto.StartAgentRunRequest;

/**
 * Agent Run 准备编排：同步持久化请求事实，再交由后台协调器执行。
 */
public interface AgentRunAppService {

    /**
     * 校验请求、保存 USER 和 PENDING Run，并在事务提交后放入后台执行队列。
     */
    AgentRunPreparation prepare(Long sessionId, StartAgentRunRequest request, String traceId);
}
