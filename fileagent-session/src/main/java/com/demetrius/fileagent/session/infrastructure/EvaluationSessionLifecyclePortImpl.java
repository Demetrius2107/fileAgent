package com.demetrius.fileagent.session.infrastructure;

import com.demetrius.fileagent.api.port.EvaluationSessionLifecyclePort;
import com.demetrius.fileagent.session.domain.SessionEntity;
import com.demetrius.fileagent.session.domain.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评测专用临时会话适配器：仅清理会话和消息，跨域 Agent 审计事实由其自身保留策略管理。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
@Component
@RequiredArgsConstructor
public class EvaluationSessionLifecyclePortImpl implements EvaluationSessionLifecyclePort {

    private final SessionRepository sessionRepository;

    @Override
    @Transactional
    public Long create(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("评测会话标题不能为空");
        }
        SessionEntity session = new SessionEntity();
        session.setTitle(title.strip());
        SessionEntity saved = sessionRepository.save(session);
        if (saved.getId() == null) {
            throw new IllegalStateException("评测会话保存后缺少 ID");
        }
        return saved.getId();
    }

    @Override
    @Transactional
    public void delete(Long sessionId) {
        if (sessionId != null && sessionRepository.existsById(sessionId)) {
            sessionRepository.deleteById(sessionId);
        }
    }
}
