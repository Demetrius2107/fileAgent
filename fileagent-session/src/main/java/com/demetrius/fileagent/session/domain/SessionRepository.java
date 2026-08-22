package com.demetrius.fileagent.session.domain;

import java.util.List;
import java.util.Optional;

/**
 * 会话领域仓储（领域契约，纯接口）。
 * 由 fileagent-session 的 infrastructure 层提供 Spring Data JPA 实现。
 */
public interface SessionRepository {

    SessionEntity save(SessionEntity session);

    Optional<SessionEntity> findById(Long id);

    List<SessionEntity> findAll();

    boolean existsById(Long id);

    void deleteById(Long id);
}
