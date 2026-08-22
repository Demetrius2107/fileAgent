package com.demetrius.fileagent.session.infrastructure;

import com.demetrius.fileagent.api.dto.MessageDto;
import com.demetrius.fileagent.api.port.SessionQueryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 会话域对外端口实现（骨架声明，由协作者实现映射逻辑，M1）。
 * 供 chat 等域查询会话/消息使用，避免直接依赖 session 实体。
 */
@Component
@RequiredArgsConstructor
public class SessionQueryPortImpl implements SessionQueryPort {

    private final SessionJpaRepository sessionJpaRepository;
    private final MessageJpaRepository messageJpaRepository;

    @Override
    public Optional<SessionBrief> findById(Long sessionId) {
        throw new UnsupportedOperationException("M1: 由协作者实现");
    }

    @Override
    public boolean exists(Long sessionId) {
        return sessionJpaRepository.existsById(sessionId);
    }

    @Override
    public List<MessageDto> listMessages(Long sessionId) {
        throw new UnsupportedOperationException("M1: 由协作者实现");
    }
}
