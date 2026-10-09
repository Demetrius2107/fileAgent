package com.demetrius.fileagent.agent.infrastructure.state;

import io.agentscope.core.state.AgentState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 恢复前的强制状态加载器：检查点不可验证时直接失败，禁止 AgentScope 静默创建全新状态。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
@Component
@RequiredArgsConstructor
public class StrictAgentStateLoader {

    private final DatabaseAgentStateStore stateStore;

    /**
     * 返回已验证的 AgentState；调用方必须在创建恢复用 ReActAgent 前先调用此方法。
     */
    public AgentState loadVerified(String runId) {
        return stateStore.loadVerifiedAgentState(runId);
    }
}
