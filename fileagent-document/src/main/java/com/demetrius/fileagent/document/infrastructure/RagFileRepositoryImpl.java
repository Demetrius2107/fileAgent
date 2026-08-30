package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.document.domain.RagFileEntity;
import com.demetrius.fileagent.document.domain.RagFileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * {@link RagFileRepository} 的 JPA 适配实现。
 */
@Component
@RequiredArgsConstructor
public class RagFileRepositoryImpl implements RagFileRepository {

    private final RagFileJpaRepository jpaRepository;

    @Override
    public RagFileEntity save(RagFileEntity ragFile) {
        return jpaRepository.save(ragFile);
    }

    @Override
    public Optional<RagFileEntity> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public List<RagFileEntity> findByKnowledgeTag(String knowledgeTag) {
        return jpaRepository.findByKnowledgeTag(knowledgeTag);
    }

    @Override
    public List<RagFileEntity> findAllOrderByCreatedAtDesc() {
        return jpaRepository.findAllByOrderByCreatedAtDesc();
    }

    @Override
    public void delete(RagFileEntity ragFile) {
        jpaRepository.delete(ragFile);
    }
}
