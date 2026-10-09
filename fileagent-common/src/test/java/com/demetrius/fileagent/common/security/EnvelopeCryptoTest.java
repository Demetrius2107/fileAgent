package com.demetrius.fileagent.common.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 版本化信封加密的 AAD 绑定、篡改拒绝和 HMAC 稳定性验证。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
class EnvelopeCryptoTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRoundTripVersionedEnvelopeAndProduceStableHmac() {
        EnvelopeCrypto crypto = newCrypto("master-one");

        String envelope = crypto.encrypt("secret", "agent_checkpoint:run-1:agentscope:v1");

        assertThat(envelope).startsWith("v1:local:");
        assertThat(crypto.decrypt(envelope, "agent_checkpoint:run-1:agentscope:v1")).isEqualTo("secret");
        assertThat(crypto.hmac("canonical-input")).isEqualTo(crypto.hmac("canonical-input"));
        assertThat(crypto.hmac("canonical-input")).isNotEqualTo(crypto.hmac("different-input"));
    }

    @Test
    void shouldRejectDifferentAadTamperedEnvelopeAndUnknownKeyId() {
        EnvelopeCrypto crypto = newCrypto("master-one");
        String envelope = crypto.encrypt("secret", "agent_checkpoint:run-1:agentscope:v1");
        int payloadStart = "v1:local:".length();
        String tampered = envelope.substring(0, payloadStart)
                + (envelope.charAt(payloadStart) == 'A' ? "B" : "A")
                + envelope.substring(payloadStart + 1);

        assertThatThrownBy(() -> crypto.decrypt(envelope, "agent_checkpoint:run-2:agentscope:v1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> crypto.decrypt(tampered, "agent_checkpoint:run-1:agentscope:v1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> crypto.decrypt(envelope.replaceFirst("v1:local:", "v1:other:"),
                "agent_checkpoint:run-1:agentscope:v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("keyId");
    }

    private EnvelopeCrypto newCrypto(String keyFile) {
        LocalMasterKeyProvider provider = new LocalMasterKeyProvider(tempDir.resolve(keyFile).toString());
        return new EnvelopeCrypto(new AesGcmCipher(provider), provider);
    }
}
