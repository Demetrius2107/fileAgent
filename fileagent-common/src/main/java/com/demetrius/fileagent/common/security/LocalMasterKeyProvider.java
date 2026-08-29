package com.demetrius.fileagent.common.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.KeyGenerator;

/**
 * 本地主密钥提供者：为 API Key 等敏感信息的加密提供 AES 主密钥。
 * <p>
 * 主密钥首次使用时自动生成并写入 {@code fileagent.secret-key-path}（默认 storage/ 下，
 * 该目录已在 .gitignore），之后每次启动从文件加载。密钥只存在本机，不进仓库、不外发。
 */
@Slf4j
@Component
public class LocalMasterKeyProvider {

    private final Path keyPath;

    private SecretKey masterKey;

    public LocalMasterKeyProvider(@Value("${fileagent.secret-key-path:./storage/secret.key}") String keyPath) {
        this.keyPath = Paths.get(keyPath).toAbsolutePath().normalize();
    }

    /**
     * 获取主密钥（懒加载）：文件存在则读取，不存在则生成 AES-256 密钥并落盘。
     *
     * @return AES 主密钥
     * @throws IllegalStateException 密钥文件读写或密钥生成失败时
     */
    public synchronized SecretKey masterKey() {
        if (masterKey != null) {
            return masterKey;
        }
        try {
            if (Files.exists(keyPath)) {
                byte[] encoded = Files.readAllBytes(keyPath);
                masterKey = new SecretKeySpec(encoded, "AES");
                log.debug("主密钥已加载: {}", keyPath);
            } else {
                KeyGenerator generator = KeyGenerator.getInstance("AES");
                generator.init(256, new SecureRandom());
                masterKey = generator.generateKey();
                Files.createDirectories(keyPath.getParent());
                Files.write(keyPath, masterKey.getEncoded());
                log.info("已生成本地主密钥: {}", keyPath);
            }
            return masterKey;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("本地主密钥初始化失败: " + keyPath, e);
        }
    }
}
