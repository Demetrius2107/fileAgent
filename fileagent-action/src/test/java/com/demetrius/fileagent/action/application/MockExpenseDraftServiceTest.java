package com.demetrius.fileagent.action.application;

import com.demetrius.fileagent.action.infrastructure.persistence.MockExpenseDraftJpaRepository;
import com.demetrius.fileagent.common.security.AesGcmCipher;
import com.demetrius.fileagent.common.security.EnvelopeCrypto;
import com.demetrius.fileagent.common.security.LocalMasterKeyProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mock 报销草稿的持久化幂等行为测试。
 *
 * @author raosaijie
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MockExpenseDraftServiceTest {

    @Autowired
    private MockExpenseDraftJpaRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TempDir
    private Path tempDir;

    private MockExpenseDraftService service;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        LocalMasterKeyProvider keyProvider = new LocalMasterKeyProvider(tempDir.resolve("master.key").toString());
        EnvelopeCrypto crypto = new EnvelopeCrypto(new AesGcmCipher(keyProvider), keyProvider);
        service = new MockExpenseDraftService(repository, crypto, new ObjectMapper());
    }

    @Test
    void shouldReplaySameRequestWithoutCreatingAnotherDraft() {
        var created = service.create("operation-1", new BigDecimal("100.00"), "测试", "account-secret");
        var replayed = service.create("operation-1", new BigDecimal("100.000"), "测试", "account-secret");

        assertThat(created.idempotentReplay()).isFalse();
        assertThat(replayed.idempotentReplay()).isTrue();
        assertThat(replayed.draftId()).isEqualTo(created.draftId());
        assertThat(repository.count()).isEqualTo(1);
        assertThat(service.find(created.draftId())).isPresent();
        assertThat(jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_name = 'ACTION_MOCK_EXPENSE_DRAFT'",
                String.class)).doesNotContain("BANK_ACCOUNT");
    }

    @Test
    void shouldRejectSameKeyWithDifferentSensitiveInput() {
        service.create("operation-2", new BigDecimal("100"), "测试", "account-a");

        assertThatThrownBy(() -> service.create("operation-2", new BigDecimal("100"), "测试", "account-b"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void shouldRejectInvalidKeysAndAmount() {
        assertThatThrownBy(() -> service.create(" ", BigDecimal.ONE, "测试", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("operation-3", BigDecimal.ZERO, "测试", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.count()).isZero();
    }

    @Test
    void concurrentSameKeyShouldCreateOneDraft() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(6)) {
            var futures = new ArrayList<java.util.concurrent.Future<MockExpenseDraftService.DraftResult>>();
            for (int index = 0; index < 6; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return service.create("concurrent-key", new BigDecimal("12.50"), "test", "account");
                }));
            }
            start.countDown();
            var results = new ArrayList<MockExpenseDraftService.DraftResult>();
            for (var future : futures) {
                results.add(future.get());
            }
            assertThat(results).extracting(MockExpenseDraftService.DraftResult::draftId)
                    .hasSize(6).containsOnly(results.getFirst().draftId());
            assertThat(results.stream().filter(result -> !result.idempotentReplay())).hasSize(1);
            assertThat(repository.count()).isEqualTo(1);
        }
    }
}
