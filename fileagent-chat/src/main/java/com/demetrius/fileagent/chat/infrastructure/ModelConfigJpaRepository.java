package com.demetrius.fileagent.chat.infrastructure;

import com.demetrius.fileagent.chat.domain.ModelConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 模型 Provider 配置 JPA 仓储（Spring Data 自动实现）。
 * 领域契约见 {@link com.demetrius.fileagent.chat.domain.ModelConfigRepository}。
 */
public interface ModelConfigJpaRepository extends JpaRepository<ModelConfigEntity, Long> {

    Optional<ModelConfigEntity> findByActiveTrue();

    /** 全部配置，按创建时间倒序（最新添加在前） */
    List<ModelConfigEntity> findAllByOrderByCreatedAtDesc();
}
