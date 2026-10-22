package com.demetrius.fileagent.action.infrastructure.persistence;

import com.demetrius.fileagent.action.domain.MockExpenseDraftEntity;
import com.demetrius.fileagent.action.domain.MockExpenseDraftRepository;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Mock 草稿的 JPA 存储。
 *
 * @author raosaijie
 */
public interface MockExpenseDraftJpaRepository
        extends JpaRepository<MockExpenseDraftEntity, String>, MockExpenseDraftRepository {
}
