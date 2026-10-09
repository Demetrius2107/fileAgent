package com.demetrius.fileagent.agent.application.runtime;

import com.demetrius.fileagent.api.dto.AgentRunCommand;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import reactor.core.publisher.Flux;

/**
 * 单次 Agent 执行端口：由协调器在后台线程调用，不拥有排队、租约或 SSE 订阅生命周期。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
public interface AgentExecutionEngine {

    /**
     * 执行已验证的 Run 命令并返回受控运行事件。
     */
    Flux<AgentRunEvent> execute(AgentRunCommand command);
}
