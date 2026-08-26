package com.demetrius.fileagent.session.infrastructure;

import com.demetrius.fileagent.session.domain.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 会话 JPA 仓储（Spring Data 自动实现）。
 * 领域契约见 {@link com.demetrius.fileagent.session.domain.SessionRepository}。
 */
public interface SessionJpaRepository extends JpaRepository<SessionEntity, Long> {

    /** 全部会话，按更新时间倒序（最近活跃在前） */
    List<SessionEntity> findAllByOrderByUpdatedAtDesc();
}
