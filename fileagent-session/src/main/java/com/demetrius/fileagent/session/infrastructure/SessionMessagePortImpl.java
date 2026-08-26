package com.demetrius.fileagent.session.infrastructure;

import com.demetrius.fileagent.api.enums.MessageType;
import com.demetrius.fileagent.api.port.SessionMessagePort;
import com.demetrius.fileagent.common.exception.BizException;
import com.demetrius.fileagent.session.domain.MessageEntity;
import com.demetrius.fileagent.session.domain.SessionEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 会话消息写入端口实现：追加消息并刷新会话活跃时间。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
@Component
@RequiredArgsConstructor
public class SessionMessagePortImpl implements SessionMessagePort {

    private final SessionJpaRepository sessionJpaRepository;
    private final MessageJpaRepository messageJpaRepository;

    @Override
    @Transactional
    public Long append(Long sessionId, MessageType type, String content) {
        SessionEntity session = sessionJpaRepository.findById(sessionId)
                .orElseThrow(() -> new BizException(404, "会话不存在"));
        MessageEntity message = new MessageEntity();
        message.setSession(session);
        message.setRole(type);
        message.setContent(content);
        MessageEntity saved = messageJpaRepository.save(message);
        session.setUpdatedAt(LocalDateTime.now());
        sessionJpaRepository.save(session);
        return saved.getId();
    }
}
