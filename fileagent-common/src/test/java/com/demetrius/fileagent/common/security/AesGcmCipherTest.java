package com.demetrius.fileagent.common.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AesGcmCipher} 加解密回环测试：密文可解、随机 IV 密文不同、
 * 错误密文/换主密钥解密失败。
 *
 * @author Demetrius
 * @since 0.1.0
 * @date 2026-08-29
 */
class AesGcmCipherTest {

    @TempDir
    Path tempDir;

    @Test
    void encryptDecryptShouldRoundTrip() {
        AesGcmCipher cipher = newCipher("master-one");
        String plain = "sk-7f3a9b2c1d8e4f60a5b6c7d8e9f0a1b2";

        String encrypted = cipher.encrypt(plain);

        assertThat(encrypted).isNotEqualTo(plain);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(plain);
    }

    @Test
    void encryptShouldProduceDifferentCiphertextForEachCall() {
        AesGcmCipher cipher = newCipher("master-one");

        // 随机 IV：同一明文两次加密产生不同密文
        assertThat(cipher.encrypt("same-key")).isNotEqualTo(cipher.encrypt("same-key"));
    }

    @Test
    void decryptShouldFailOnGarbageOrWrongMasterKey() {
        AesGcmCipher cipher = newCipher("master-one");
        String encrypted = cipher.encrypt("secret");

        assertThatThrownBy(() -> cipher.decrypt("not-base64-!!"))
                .isInstanceOf(IllegalStateException.class);
        // 换了主密钥（另一台机器/重装）后旧密文不可解
        AesGcmCipher other = newCipher("master-two");
        assertThatThrownBy(() -> other.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    private AesGcmCipher newCipher(String keyFile) {
        return new AesGcmCipher(new LocalMasterKeyProvider(tempDir.resolve(keyFile).toString()));
    }
}
