package com.demetrius.fileagent.common.security;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 版本化敏感载荷信封：以 AES-GCM 绑定 AAD，并提供独立域隔离的稳定 HMAC。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-09
 */
@Component
@RequiredArgsConstructor
public class EnvelopeCrypto {

    private static final String VERSION = "v1";
    private static final String KEY_ID = "local";
    private static final String HMAC_CONTEXT = "fileagent-hmac-v1";

    private final AesGcmCipher aesGcmCipher;
    private final LocalMasterKeyProvider masterKeyProvider;

    /**
     * 加密明文并绑定业务 AAD，返回 {@code v1:local:<base64>} 格式的自描述信封。
     *
     * @param plaintext 待加密的非空明文
     * @param aad 业务绑定数据；解密时必须逐字一致
     * @return 可持久化的版本化密文信封
     */
    public String encrypt(String plaintext, String aad) {
        return VERSION + ':' + KEY_ID + ':' + aesGcmCipher.encrypt(requireText(plaintext, "plaintext"),
                requireText(aad, "aad"));
    }

    /**
     * 解密版本化信封并校验版本、keyId 与 AAD；任何不匹配都拒绝返回明文。
     *
     * @param envelope 由 {@link #encrypt(String, String)} 生成的密文信封
     * @param aad 加密时绑定的业务数据
     * @return 原始明文
     */
    public String decrypt(String envelope, String aad) {
        String[] parts = requireText(envelope, "envelope").split(":", 3);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new IllegalStateException("不支持的密文信封版本");
        }
        if (!KEY_ID.equals(parts[1])) {
            throw new IllegalStateException("不支持的密文 keyId: " + parts[1]);
        }
        if (parts[2].isBlank()) {
            throw new IllegalStateException("密文信封载荷不能为空");
        }
        return aesGcmCipher.decrypt(parts[2], requireText(aad, "aad"));
    }

    /**
     * 对规范化业务输入生成稳定 HMAC；密钥由本地主密钥与固定上下文派生，不能与 AES 直接复用。
     *
     * @param canonicalText 已完成确定性规范化的业务输入
     * @return Base64 URL 编码的 HMAC-SHA-256 摘要
     */
    public String hmac(String canonicalText) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKeyBytes(), "HmacSHA256"));
            byte[] digest = mac.doFinal(requireText(canonicalText, "canonicalText")
                    .getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    private byte[] hmacKeyBytes() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(HMAC_CONTEXT.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(masterKeyProvider.masterKey().getEncoded());
            return digest.digest();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 密钥派生失败", e);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
