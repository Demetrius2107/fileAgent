package com.demetrius.fileagent.document.domain;

import java.util.List;
import java.util.Optional;

/**
 * 文档领域仓储（领域契约，纯接口）。
 * 由 fileagent-document 的 infrastructure 层提供 Spring Data JPA 实现。
 */
public interface DocumentRepository {

    DocumentEntity save(DocumentEntity document);

    Optional<DocumentEntity> findById(Long id);

    List<DocumentEntity> findBySessionId(Long sessionId);

    Optional<DocumentEntity> findBySha256(String sha256);

    boolean existsById(Long id);

    /** 判断会话文档是否仍引用指定原件存储键。 */
    boolean existsByStoragePath(String storagePath);

    void deleteById(Long id);
}
