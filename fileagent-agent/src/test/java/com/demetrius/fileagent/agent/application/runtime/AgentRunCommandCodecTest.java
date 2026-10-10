package com.demetrius.fileagent.agent.application.runtime;

import com.demetrius.fileagent.agent.domain.run.AgentRunEntity;
import com.demetrius.fileagent.api.dto.AgentRunCommand;
import com.demetrius.fileagent.api.dto.KnowledgeScope;
import com.demetrius.fileagent.common.security.AesGcmCipher;
import com.demetrius.fileagent.common.security.EnvelopeCrypto;
import com.demetrius.fileagent.common.security.LocalMasterKeyProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 持久化 Run 请求的密封编解码与完整性校验测试。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
class AgentRunCommandCodecTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withBean(EnvelopeCrypto.class, () -> mock(EnvelopeCrypto.class))
            .withBean(AgentRunCommandCodec.class);

    @TempDir
    Path tempDir;

    @Test
    void shouldEncryptAndRestoreCommandForSameRun() {
        AgentRunCommandCodec codec = codec();
        AgentRunCommand command = command("run-1", 1L);

        AgentRunCommandCodec.EncodedCommand encoded = codec.encrypt(command);
        AgentRunEntity run = AgentRunEntity.pending("run-1", 1L, "trace-run-1",
                "local-operator", encoded.ciphertext(), encoded.hmac());

        assertThat(encoded.ciphertext()).doesNotContain("不应明文持久化的问题");
        assertThat(codec.decrypt(run)).isEqualTo(command);
    }

    @Test
    void shouldRejectTamperedRequestHmac() {
        AgentRunCommandCodec codec = codec();
        AgentRunCommandCodec.EncodedCommand encoded = codec.encrypt(command("run-2", 2L));
        AgentRunEntity run = AgentRunEntity.pending("run-2", 2L, "trace-run-2",
                "local-operator", encoded.ciphertext(), "tampered-hmac");

        assertThatThrownBy(() -> codec.decrypt(run))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("完整性校验失败");
    }

    @Test
    void shouldWireCodecWithBootManagedObjectMapper() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(ObjectMapper.class)
                .hasSingleBean(AgentRunCommandCodec.class));
    }

    private AgentRunCommandCodec codec() {
        LocalMasterKeyProvider provider = new LocalMasterKeyProvider(tempDir.resolve("master.key").toString());
        EnvelopeCrypto crypto = new EnvelopeCrypto(new AesGcmCipher(provider), provider);
        return new AgentRunCommandCodec(new ObjectMapper(), crypto);
    }

    private AgentRunCommand command(String runId, Long sessionId) {
        return new AgentRunCommand(runId, sessionId, "trace-" + runId,
                "不应明文持久化的问题", List.of(), KnowledgeScope.global());
    }
}
