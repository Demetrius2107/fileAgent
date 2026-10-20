package com.demetrius.fileagent.action.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * 副作用执行账本仓储契约：提供三类标识查询和带版本条件的单次执行抢占。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-20
 */
public interface ActionToolExecutionRepository {

    <S extends ActionToolExecutionEntity> S save(S execution);

    Optional<ActionToolExecutionEntity> findByExecutionId(String executionId);

    Optional<ActionToolExecutionEntity> findByExecutionKey(String executionKey);

    Optional<ActionToolExecutionEntity> findByOperationId(String operationId);

    Optional<ActionToolExecutionEntity> findByRunIdAndOperationFingerprint(String runId,
                                                                           String operationFingerprint);

    /**
     * 仅当记录仍为 PENDING 且版本匹配时原子进入 EXECUTING，返回值为 1 表示抢占成功。
     */
    int beginPending(String executionId, long expectedVersion, Instant now);
}
