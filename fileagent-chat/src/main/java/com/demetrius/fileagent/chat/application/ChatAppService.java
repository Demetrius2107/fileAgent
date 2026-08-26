package com.demetrius.fileagent.chat.application;

import com.demetrius.fileagent.api.dto.ChatReq;
import com.demetrius.fileagent.api.dto.ChatStreamEvent;
import com.demetrius.fileagent.api.port.ChatExecutionPort;
import reactor.core.publisher.Flux;

/**
 * 对话/推理应用服务（用例契约，核心域）。
 * 由协作者提供 {@code ChatAppServiceImpl} 实现（M2 RAG 流式闭环；M3 ReAct）。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
public interface ChatAppService extends ChatExecutionPort {

    /** 便捷重载：接收 {@link ChatReq} 包装 */
    default Flux<ChatStreamEvent> chat(Long sessionId, ChatReq req) {
        return chat(sessionId, req.prompt());
    }
}
