package com.demetrius.fileagent.api.port;

import com.demetrius.fileagent.api.enums.MessageType;

/**
 * 会话消息写入端口（由 fileagent-session 的 infrastructure 实现）。
 * Chat 域落库用户/助手消息必须走本接口，禁止直接依赖 session 域实体。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
public interface SessionMessagePort {

    /**
     * 向指定会话追加一条消息。
     *
     * @param sessionId 会话 id
     * @param type      消息角色
     * @param content   消息正文
     * @return 新消息 id
     */
    Long append(Long sessionId, MessageType type, String content);
}
