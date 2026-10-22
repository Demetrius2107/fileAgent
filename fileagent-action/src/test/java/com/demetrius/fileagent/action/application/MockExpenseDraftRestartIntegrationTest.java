package com.demetrius.fileagent.action.application;

import com.demetrius.fileagent.action.infrastructure.persistence.MockExpenseDraftJpaRepository;
import com.demetrius.fileagent.common.security.AesGcmCipher;
import com.demetrius.fileagent.common.security.EnvelopeCrypto;
import com.demetrius.fileagent.common.security.LocalMasterKeyProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 关闭并重新创建应用上下文，验证 Mock 草稿及幂等摘要在 H2 文件库中保留。
 *
 * @author raosaijie
 */
class MockExpenseDraftRestartIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldReadAndReplayDraftAfterRestart() {
        String draftId;
        try (var first = startContext()) {
            draftId = service(first).create("restart-key", new BigDecimal("35.00"), "test", "account-secret").draftId();
        }
        try (var second = startContext()) {
            var service = service(second);
            assertThat(service.find(draftId)).isPresent();
            assertThat(service.create("restart-key", new BigDecimal("35"), "test", "account-secret"))
                    .satisfies(result -> {
                        assertThat(result.draftId()).isEqualTo(draftId);
                        assertThat(result.idempotentReplay()).isTrue();
                    });
            assertThatThrownBy(() -> service.create("restart-key", new BigDecimal("35"), "test", "other"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(second.getBean(MockExpenseDraftJpaRepository.class).count()).isEqualTo(1);
        }
    }

    private ConfigurableApplicationContext startContext() {
        return new SpringApplicationBuilder(TestConfiguration.class).web(WebApplicationType.NONE).properties(
                "spring.datasource.url=jdbc:h2:file:" + tempDir.resolve("mock-db"),
                "spring.jpa.hibernate.ddl-auto=update",
                "spring.main.banner-mode=off",
                "logging.level.org.springframework=WARN",
                "logging.level.org.hibernate=WARN").run();
    }

    private MockExpenseDraftService service(ConfigurableApplicationContext context) {
        var provider = new LocalMasterKeyProvider(tempDir.resolve("master.key").toString());
        return new MockExpenseDraftService(context.getBean(MockExpenseDraftJpaRepository.class),
                new EnvelopeCrypto(new AesGcmCipher(provider), provider), new ObjectMapper());
    }

    @Configuration
    @EnableAutoConfiguration
    @AutoConfigurationPackage(basePackages = "com.demetrius.fileagent.action")
    static class TestConfiguration {
    }
}
