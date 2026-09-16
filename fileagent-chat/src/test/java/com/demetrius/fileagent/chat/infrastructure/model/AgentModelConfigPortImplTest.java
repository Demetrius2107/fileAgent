package com.demetrius.fileagent.chat.infrastructure.model;

import com.demetrius.fileagent.api.dto.AgentModelConfig;
import com.demetrius.fileagent.api.enums.ModelProvider;
import com.demetrius.fileagent.chat.domain.ModelConfigEntity;
import com.demetrius.fileagent.chat.domain.ModelConfigRepository;
import com.demetrius.fileagent.common.security.AesGcmCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentModelConfigPortImplTest {

    private ModelConfigRepository repository;
    private AesGcmCipher cipher;
    private AgentModelConfigPortImpl port;

    @BeforeEach
    void setUp() {
        repository = mock(ModelConfigRepository.class);
        cipher = mock(AesGcmCipher.class);
        port = new AgentModelConfigPortImpl(
                repository, cipher, "https://api.deepseek.com", "env-key", "configured-default-model", 0.3);
    }

    @Test
    void currentShouldPreferEnabledDatabaseChatModel() {
        ModelConfigEntity entity = new ModelConfigEntity();
        entity.setProvider(ModelProvider.DEEPSEEK);
        entity.setBaseUrl("https://api.deepseek.com/v1");
        entity.setApiKeyCipher("cipher-text");
        entity.setChatModel("deepseek-v4-pro");
        entity.setTemperature(0.5);
        when(repository.findActive()).thenReturn(Optional.of(entity));
        when(cipher.decrypt("cipher-text")).thenReturn("test-key");

        AgentModelConfig config = port.current();

        assertThat(config.model()).isEqualTo("deepseek-v4-pro");
        assertThat(config.apiKey()).isEqualTo("test-key");
        assertThat(config.provider()).isEqualTo(ModelProvider.DEEPSEEK);
        assertThat(config.temperature()).isEqualTo(0.5);
    }

    @Test
    void currentShouldUseEnvironmentDefaultsWhenNoDatabaseConfigExists() {
        when(repository.findActive()).thenReturn(Optional.empty());

        AgentModelConfig config = port.current();

        assertThat(config.model()).isEqualTo("configured-default-model");
        assertThat(config.apiKey()).isEqualTo("env-key");
        assertThat(config.provider()).isEqualTo(ModelProvider.DEEPSEEK);
        assertThat(config.temperature()).isEqualTo(0.3);
    }

    @Test
    void currentShouldFallBackWhenActiveKeyDecryptFails() {
        ModelConfigEntity entity = new ModelConfigEntity();
        entity.setProvider(ModelProvider.DEEPSEEK);
        entity.setApiKeyCipher("broken-cipher");
        entity.setChatModel("deepseek-v4-pro");
        when(repository.findActive()).thenReturn(Optional.of(entity));
        when(cipher.decrypt("broken-cipher")).thenThrow(new IllegalStateException("bad key"));

        AgentModelConfig config = port.current();

        assertThat(config.model()).isEqualTo("configured-default-model");
    }
}
