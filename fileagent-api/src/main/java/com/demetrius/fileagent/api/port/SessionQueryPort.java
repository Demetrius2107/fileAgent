package com.demetrius.fileagent.api.port;

import com.demetrius.fileagent.api.dto.MessageDto;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 会话域对外端口（由 fileagent-session 的 infrastructure 实现）。
 * 其它域获取会话/消息数据必须走本接口，禁止直接依赖 session 域实体。
 */
public interface SessionQueryPort {

    Optional<SessionBrief> findById(Long sessionId);

    boolean exists(Long sessionId);

    /** 会话内消息历史（按时间正序） */
    List<MessageDto> listMessages(Long sessionId);

    /** 读取会话当前滚动摘要及其消息检查点。 */
    SessionSummary getSummary(Long sessionId);

    /** 读取指定消息之后的历史（按时间正序）。 */
    List<MessageDto> listMessagesAfter(Long sessionId, Long afterMessageId);

    /** 轻量会话概要（跨域最小暴露） */
    record SessionBrief(Long id, String title) {
    }

    /** 会话滚动摘要的最小跨域视图。 */
    record SessionSummary(
            String content,
            Long throughMessageId,
            Instant updatedAt,
            long version,
            String sourceHash) {

        public static SessionSummary empty() {
            return new SessionSummary(null, null, null, 0L, null);
        }
    }
}
