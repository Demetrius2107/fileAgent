package com.demetrius.fileagent.common.security;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM 加解密组件：用于 API Key 等敏感信息的落库加密。
 * <p>
 * 每次加密使用随机 12 字节 IV，密文格式为 Base64(IV + 密文)，认证标签 128 位。
 * 主密钥来自 {@link LocalMasterKeyProvider}，只存在于本机 storage 目录。
 */
@Component
@RequiredArgsConstructor
public class AesGcmCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final LocalMasterKeyProvider masterKeyProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 加密明文，返回 Base64(IV + 密文)。
     *
     * @param plainText 明文（不允许为空）
     * @return Base64 编码的密文
     * @throws IllegalStateException 加密失败时
     */
    public String encrypt(String plainText) {
        return encrypt(plainText, null);
    }

    /**
     * 加密明文并将其绑定到附加认证数据；AAD 不会写入密文，但解密时必须完全一致。
     *
     * @param plainText 明文（不允许为空）
     * @param aad 附加认证数据；为空时保持历史调用的兼容行为
     * @return Base64 编码的密文
     */
    public String encrypt(String plainText, String aad) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, masterKeyProvider.masterKey(), new GCMParameterSpec(TAG_BITS, iv));
            applyAad(cipher, aad);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv).put(encrypted).array());
        } catch (Exception e) {
            throw new IllegalStateException("敏感信息加密失败", e);
        }
    }

    /**
     * 解密 {@link #encrypt(String)} 产生的密文。
     *
     * @param cipherText Base64(IV + 密文)
     * @return 明文
     * @throws IllegalStateException 密文非法或主密钥不匹配时
     */
    public String decrypt(String cipherText) {
        return decrypt(cipherText, null);
    }

    /**
     * 解密并校验附加认证数据；AAD 与加密时不一致会被 GCM 认证标签拒绝。
     *
     * @param cipherText Base64(IV + 密文)
     * @param aad 加密时绑定的附加认证数据；为空时保持历史调用的兼容行为
     * @return 明文
     * @throws IllegalStateException 密文、AAD 或主密钥不匹配时
     */
    public String decrypt(String cipherText, String aad) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(cipherText));
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, masterKeyProvider.masterKey(), new GCMParameterSpec(TAG_BITS, iv));
            applyAad(cipher, aad);
            return new String(cipher.doFinal(encrypted), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("敏感信息解密失败（密文损坏或主密钥不匹配）", e);
        }
    }

    private void applyAad(Cipher cipher, String aad) {
        if (aad != null) {
            cipher.updateAAD(aad.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
