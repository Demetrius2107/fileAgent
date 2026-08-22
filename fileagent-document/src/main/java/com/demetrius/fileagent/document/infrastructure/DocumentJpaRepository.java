package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.document.domain.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 文档 JPA 仓储（Spring Data 自动实现）。
 * 领域契约见 {@link com.demetrius.fileagent.document.domain.DocumentRepository}。
 */
public interface DocumentJpaRepository extends JpaRepository<DocumentEntity, Long> {

    List<DocumentEntity> findBySessionId(Long sessionId);

    Optional<DocumentEntity> findBySha256(String sha256);
}
