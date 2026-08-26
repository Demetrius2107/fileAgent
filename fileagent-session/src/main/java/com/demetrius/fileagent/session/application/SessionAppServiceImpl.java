package com.demetrius.fileagent.session.application;

import com.demetrius.fileagent.api.dto.CreateSessionReq;
import com.demetrius.fileagent.api.dto.MessageDto;
import com.demetrius.fileagent.api.dto.SessionDto;
import com.demetrius.fileagent.api.port.SessionQueryPort;
import com.demetrius.fileagent.common.exception.BizException;
import com.demetrius.fileagent.session.domain.SessionEntity;
import com.demetrius.fileagent.session.domain.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会话应用服务实现：创建、列表（最近活跃优先）与消息查询。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
@Service
@RequiredArgsConstructor
public class SessionAppServiceImpl implements SessionAppService {

    private static final String DEFAULT_TITLE = "新会话";

    private final SessionRepository sessionRepository;
    private final SessionQueryPort sessionQueryPort;

    @Override
    public SessionDto createSession(CreateSessionReq req) {
        String title = normalizeTitle(req.title());
        SessionEntity entity = new SessionEntity();
        entity.setTitle(title);
        SessionEntity saved = sessionRepository.save(entity);
        return toDto(saved);
    }

    @Override
    public List<SessionDto> listSessions() {
        return sessionRepository.findAll().stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public List<MessageDto> listMessages(Long sessionId) {
        if (!sessionQueryPort.exists(sessionId)) {
            throw new BizException(404, "会话不存在");
        }
        return sessionQueryPort.listMessages(sessionId);
    }

    private String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return DEFAULT_TITLE;
        }
        return title.trim();
    }

    private SessionDto toDto(SessionEntity entity) {
        return new SessionDto(
                entity.getId(),
                entity.getTitle(),
                entity.getCreatedAt().toString());
    }
}
