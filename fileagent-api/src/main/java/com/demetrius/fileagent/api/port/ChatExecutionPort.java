package com.demetrius.fileagent.api.port;

import com.demetrius.fileagent.api.dto.ChatResp;

/**
 * 对话域对外端口（由 fileagent-chat 的 application 实现）。
 * 其它域若需触发对话推理（如动作执行后回流结果）走本接口。
 */
public interface ChatExecutionPort {

    /**
     * 在指定会话内发起一次推理。
     *
     * @param sessionId 会话 id
     * @param prompt    用户输入
     * @return 结构化动作 + 展示信息
     */
    ChatResp chat(Long sessionId, String prompt);
}
