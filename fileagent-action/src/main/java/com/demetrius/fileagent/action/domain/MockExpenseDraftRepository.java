package com.demetrius.fileagent.action.domain;

import java.util.Optional;

/**
 * Mock 草稿持久化契约。
 *
 * @author raosaijie
 */
public interface MockExpenseDraftRepository {

    <S extends MockExpenseDraftEntity> S saveAndFlush(S draft);

    Optional<MockExpenseDraftEntity> findByDraftId(String draftId);

    Optional<MockExpenseDraftEntity> findByIdempotencyKey(String idempotencyKey);
}
