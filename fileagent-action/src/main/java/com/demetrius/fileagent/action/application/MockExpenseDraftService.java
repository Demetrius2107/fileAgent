package com.demetrius.fileagent.action.application;

import com.demetrius.fileagent.action.domain.MockExpenseDraftEntity;
import com.demetrius.fileagent.action.domain.MockExpenseDraftRepository;
import com.demetrius.fileagent.common.security.EnvelopeCrypto;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 负责本地 Mock 草稿的创建、幂等重放和查询。
 *
 * @author raosaijie
 */
@Service
@ConditionalOnProperty(prefix = "fileagent.action.mock", name = "enabled", havingValue = "true")
public class MockExpenseDraftService {

    private final MockExpenseDraftRepository repository;
    private final EnvelopeCrypto crypto;
    private final ObjectMapper objectMapper;

    public MockExpenseDraftService(MockExpenseDraftRepository repository, EnvelopeCrypto crypto,
                                   ObjectMapper objectMapper) {
        this.repository = repository;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
    }

    /** 创建草稿；相同幂等键及请求会返回原草稿。 */
    public DraftResult create(String key, BigDecimal amount, String memo, String bankAccount) {
        if (key == null || key.isBlank() || key.length() > 128 || key.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Idempotency-Key 无效");
        }
        BigDecimal normalizedAmount = amount == null ? null : amount.stripTrailingZeros();
        if (normalizedAmount == null || normalizedAmount.signum() <= 0 || normalizedAmount.scale() > 2
                || normalizedAmount.precision() - normalizedAmount.scale() > 10) {
            throw new IllegalArgumentException("amount 必须是大于零且最多两位小数的金额");
        }
        if (memo == null || memo.isBlank() || memo.length() > 500 || (bankAccount != null && bankAccount.length() > 100)) {
            throw new IllegalArgumentException("memo 或 bankAccount 无效");
        }
        String hmac = crypto.hmac("mock-expense-draft-v1:" + objectMapper.writeValueAsString(
                List.of(normalizedAmount.toPlainString(), memo, bankAccount == null ? "" : bankAccount)));
        Optional<MockExpenseDraftEntity> existing = repository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return replay(existing.orElseThrow(), hmac);
        }
        MockExpenseDraftEntity draft = new MockExpenseDraftEntity(UUID.randomUUID().toString(), key, hmac,
                normalizedAmount, memo, Instant.now());
        try {
            return result(repository.saveAndFlush(draft), false);
        } catch (DataIntegrityViolationException e) {
            return repository.findByIdempotencyKey(key).map(value -> replay(value, hmac)).orElseThrow(() -> e);
        }
    }

    /** 根据草稿标识查询，不包含幂等键、HMAC 或账户。 */
    public Optional<DraftResult> find(String draftId) {
        return repository.findByDraftId(draftId).map(draft -> result(draft, false));
    }

    private DraftResult replay(MockExpenseDraftEntity draft, String hmac) {
        if (!draft.getRequestHmac().equals(hmac)) {
            throw new IdempotencyConflictException();
        }
        return result(draft, true);
    }

    private DraftResult result(MockExpenseDraftEntity draft, boolean replay) {
        return new DraftResult(draft.getDraftId(), draft.getAmount(), draft.getMemo(), draft.getCreatedAt(), replay);
    }

    /** 不包含敏感输入的草稿视图。 */
    public record DraftResult(String draftId, BigDecimal amount, String memo, Instant createdAt,
                              boolean idempotentReplay) {
    }

    /** 同一幂等键不能用于不同请求。 */
    public static class IdempotencyConflictException extends IllegalStateException {
        public IdempotencyConflictException() {
            super("Idempotency-Key 已用于不同请求");
        }
    }
}
