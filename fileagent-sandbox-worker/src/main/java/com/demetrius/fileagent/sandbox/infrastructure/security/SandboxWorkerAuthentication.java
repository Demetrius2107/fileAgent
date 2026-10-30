package com.demetrius.fileagent.sandbox.infrastructure.security;

import com.demetrius.fileagent.sandbox.infrastructure.config.SandboxWorkerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Worker 内部 Bearer Token 校验边界；认证必须在读取或解析请求体前完成。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
@Component
@RequiredArgsConstructor
public class SandboxWorkerAuthentication {

    private static final String BEARER_PREFIX = "Bearer ";

    private final SandboxWorkerProperties properties;

    /** 使用常量时间比较验证固定 Worker Token，不把请求体或 Token 写入日志。 */
    public boolean matches(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return false;
        }
        String providedToken = authorization.substring(BEARER_PREFIX.length());
        String expectedToken = properties.getToken();
        return expectedToken != null && !providedToken.isBlank()
                && MessageDigest.isEqual(expectedToken.getBytes(StandardCharsets.UTF_8),
                providedToken.getBytes(StandardCharsets.UTF_8));
    }
}
