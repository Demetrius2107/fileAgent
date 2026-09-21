package com.demetrius.fileagent.session.application;

import com.demetrius.fileagent.api.dto.CreateSessionReq;
import com.demetrius.fileagent.api.dto.MessageDto;
import com.demetrius.fileagent.api.dto.RenameSessionReq;
import com.demetrius.fileagent.api.dto.SessionDto;
import com.demetrius.fileagent.api.port.SessionQueryPort;
import com.demetrius.fileagent.common.exception.BizException;
import com.demetrius.fileagent.session.domain.SessionEntity;
import com.demetrius.fileagent.session.domain.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话应用服务实现：创建、列表（最近活跃优先）与消息查询。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
@Slf4j
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

    @Override
    public SessionDto renameSession(Long id, RenameSessionReq req) {
        SessionEntity entity = findExisting(id);
        String title = req.title();
        if (!StringUtils.hasText(title)) {
            throw new BizException("会话标题不能为空");
        }
        entity.setTitle(title.trim());
        entity.setUpdatedAt(LocalDateTime.now());
        return toDto(sessionRepository.save(entity));
    }

    @Override
    public void deleteSession(Long id) {
        findExisting(id);
        // 消息随会话级联删除：SessionEntity.messages 已配 cascade=ALL + orphanRemoval。
        // 会话内上传的 document 域文件记录（sessionId 值引用，跨域）本次不清理，属已知边界。
        sessionRepository.deleteById(id);
        log.info("会话删除完成: id={}", id);
    }

    private SessionEntity findExisting(Long id) {
        return sessionRepository.findById(id)
                .orElseThrow(() -> new BizException(404, "会话不存在"));
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
