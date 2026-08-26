package com.demetrius.fileagent.api.port;

import com.demetrius.fileagent.api.dto.ChatStreamEvent;
import reactor.core.publisher.Flux;

/**
 * 对话域对外端口（由 fileagent-chat 的 application 实现）。
 * 其它域若需触发对话推理（如动作执行后回流结果）走本接口。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
public interface ChatExecutionPort {

    /**
     * 在指定会话内发起一次流式推理。
     *
     * @param sessionId 会话 id
     * @param prompt    用户输入
     * @return SSE 事件流（message / sources / done / error）
     */
    Flux<ChatStreamEvent> chat(Long sessionId, String prompt);
}
