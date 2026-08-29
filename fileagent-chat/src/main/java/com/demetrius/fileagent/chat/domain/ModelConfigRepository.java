package com.demetrius.fileagent.chat.domain;

import java.util.List;
import java.util.Optional;

/**
 * 模型 Provider 配置仓储（领域契约，纯接口）。
 * 由 fileagent-chat 的 infrastructure 层提供 Spring Data JPA 实现。
 */
public interface ModelConfigRepository {

    ModelConfigEntity save(ModelConfigEntity entity);

    Optional<ModelConfigEntity> findById(Long id);

    /** 当前启用的配置（全局至多一套） */
    Optional<ModelConfigEntity> findActive();

    /** 全部配置，按创建时间倒序（最新添加在前） */
    List<ModelConfigEntity> findAllOrderByCreatedAtDesc();

    /** 删除配置 */
    void delete(ModelConfigEntity entity);
}
