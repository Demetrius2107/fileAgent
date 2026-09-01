package com.demetrius.fileagent.chat.infrastructure;

import com.demetrius.fileagent.chat.domain.ModelConfigEntity;
import com.demetrius.fileagent.chat.domain.ModelConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * {@link ModelConfigRepository} 的 JPA 适配实现。
 */
@Component
@RequiredArgsConstructor
public class ModelConfigRepositoryImpl implements ModelConfigRepository {

    private final ModelConfigJpaRepository jpaRepository;

    @Override
    public ModelConfigEntity save(ModelConfigEntity entity) {
        return jpaRepository.save(entity);
    }

    @Override
    public Optional<ModelConfigEntity> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Optional<ModelConfigEntity> findActive() {
        return jpaRepository.findByActiveTrue();
    }

    @Override
    public List<ModelConfigEntity> findAllOrderByCreatedAtDesc() {
        return jpaRepository.findAllByOrderByCreatedAtDesc();
    }

    @Override
    public void delete(ModelConfigEntity entity) {
        jpaRepository.delete(entity);
    }
}
