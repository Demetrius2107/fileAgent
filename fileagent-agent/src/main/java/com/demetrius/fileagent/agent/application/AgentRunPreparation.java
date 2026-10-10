package com.demetrius.fileagent.agent.application;

import com.demetrius.fileagent.api.dto.AgentRunEvent;
import reactor.core.publisher.Flux;

/**
 * 同步完成持久化准备后返回给 SSE 接口的 Agent Run 订阅结果。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-10
 */
public record AgentRunPreparation(String runId, Flux<AgentRunEvent> events) {

    public AgentRunPreparation {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        if (events == null) {
            throw new IllegalArgumentException("events 不能为空");
        }
    }
}
