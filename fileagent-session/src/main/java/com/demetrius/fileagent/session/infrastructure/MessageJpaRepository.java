package com.demetrius.fileagent.session.infrastructure;

import com.demetrius.fileagent.session.domain.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 消息 JPA 仓储（Spring Data 自动实现）。
 */
public interface MessageJpaRepository extends JpaRepository<MessageEntity, Long> {

    List<MessageEntity> findBySession_IdOrderByCreatedAtAsc(Long sessionId);
}
