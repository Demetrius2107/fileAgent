package com.demetrius.fileagent.chat.application;

import com.demetrius.fileagent.api.dto.ChatReq;
import com.demetrius.fileagent.api.dto.ChatResp;
import com.demetrius.fileagent.api.port.ChatExecutionPort;

/**
 * 对话/推理应用服务（用例契约，核心域）。
 * 由协作者提供 {@code ChatAppServiceImpl} 实现（M1 RAG 闭环；M3 ReAct）。
 */
public interface ChatAppService extends ChatExecutionPort {

    /** 便捷重载：接收 {@link ChatReq} 包装 */
    ChatResp chat(Long sessionId, ChatReq req);
}
