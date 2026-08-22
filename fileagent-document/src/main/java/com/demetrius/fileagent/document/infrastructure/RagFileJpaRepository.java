package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.document.domain.RagFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 知识库文件 JPA 仓储（Spring Data 自动实现）。
 * 领域契约见 {@link com.demetrius.fileagent.document.domain.RagFileRepository}。
 */
public interface RagFileJpaRepository extends JpaRepository<RagFileEntity, Long> {

    List<RagFileEntity> findByKnowledgeTag(String knowledgeTag);
}
