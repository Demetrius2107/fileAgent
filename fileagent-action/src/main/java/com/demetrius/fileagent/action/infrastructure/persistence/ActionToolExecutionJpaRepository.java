package com.demetrius.fileagent.action.infrastructure.persistence;

import com.demetrius.fileagent.action.domain.ActionToolExecutionEntity;
import com.demetrius.fileagent.action.domain.ActionToolExecutionRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 副作用执行账本的 Spring Data JPA 实现，使用版本和状态双条件完成单次抢占。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-20
 */
public interface ActionToolExecutionJpaRepository
        extends JpaRepository<ActionToolExecutionEntity, String>, ActionToolExecutionRepository {

    @Override
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ActionToolExecutionEntity e set e.status = 'EXECUTING', e.attempt = e.attempt + 1, "
            + "e.startedAt = :now, e.updatedAt = :now, e.version = e.version + 1 "
            + "where e.executionId = :executionId and e.status = 'PENDING' and e.version = :expectedVersion")
    int beginPending(@Param("executionId") String executionId,
                     @Param("expectedVersion") long expectedVersion,
                     @Param("now") Instant now);
}
