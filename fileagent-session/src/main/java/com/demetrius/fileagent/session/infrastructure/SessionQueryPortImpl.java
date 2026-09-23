package com.demetrius.fileagent.session.infrastructure;

import com.demetrius.fileagent.api.dto.MessageDto;
import com.demetrius.fileagent.api.port.SessionQueryPort;
import com.demetrius.fileagent.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.time.ZoneId;

/**
 * 会话域对外端口实现：会话概要与消息 DTO 映射，不暴露实体。
 * 供 chat 等域查询会话/消息使用，避免直接依赖 session 实体。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
@Component
@RequiredArgsConstructor
public class SessionQueryPortImpl implements SessionQueryPort {

    private final SessionJpaRepository sessionJpaRepository;
    private final MessageJpaRepository messageJpaRepository;

    @Override
    public Optional<SessionBrief> findById(Long sessionId) {
        return sessionJpaRepository.findById(sessionId)
                .map(session -> new SessionBrief(session.getId(), session.getTitle()));
    }

    @Override
    public boolean exists(Long sessionId) {
        return sessionJpaRepository.existsById(sessionId);
    }

    @Override
    public List<MessageDto> listMessages(Long sessionId) {
        return messageJpaRepository.findBySession_IdOrderByCreatedAtAsc(sessionId).stream()
                .map(message -> toMessageDto(sessionId, message))
                .toList();
    }

    @Override
    public SessionSummary getSummary(Long sessionId) {
        return sessionJpaRepository.findById(sessionId)
                .map(session -> new SessionSummary(
                        session.getSummary(),
                        session.getSummaryThroughMessageId(),
                        session.getSummaryUpdatedAt() == null
                                ? null
                                : session.getSummaryUpdatedAt()
                                .atZone(ZoneId.systemDefault())
                                .toInstant(),
                        session.getSummaryVersion(),
                        session.getSummarySourceHash()))
                .orElseThrow(() -> new BizException(404, "会话不存在"));
    }

    @Override
    public List<MessageDto> listMessagesAfter(Long sessionId, Long afterMessageId) {
        if (afterMessageId == null) {
            return listMessages(sessionId);
        }
        return messageJpaRepository.findBySession_IdAndIdGreaterThanOrderByCreatedAtAsc(
                        sessionId, afterMessageId).stream()
                .map(message -> toMessageDto(sessionId, message))
                .toList();
    }

    private MessageDto toMessageDto(Long sessionId, com.demetrius.fileagent.session.domain.MessageEntity message) {
        return new MessageDto(
                message.getId(),
                sessionId,
                message.getRole(),
                message.getContent(),
                message.getActionJson(),
                message.getCreatedAt().toString());
    }
}
