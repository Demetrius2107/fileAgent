package com.demetrius.fileagent.session.infrastructure;

import com.demetrius.fileagent.session.domain.SessionEntity;
import com.demetrius.fileagent.session.domain.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 领域仓储到 Spring Data JPA 的适配实现。
 * {@code findAll} 语义为最近活跃优先（按 updatedAt 倒序）。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
@Component
@RequiredArgsConstructor
public class SessionRepositoryImpl implements SessionRepository {

    private final SessionJpaRepository sessionJpaRepository;

    @Override
    public SessionEntity save(SessionEntity session) {
        return sessionJpaRepository.save(session);
    }

    @Override
    public Optional<SessionEntity> findById(Long id) {
        return sessionJpaRepository.findById(id);
    }

    @Override
    public List<SessionEntity> findAll() {
        return sessionJpaRepository.findAllByOrderByUpdatedAtDesc();
    }

    @Override
    public boolean existsById(Long id) {
        return sessionJpaRepository.existsById(id);
    }

    @Override
    public void deleteById(Long id) {
        sessionJpaRepository.deleteById(id);
    }
}
