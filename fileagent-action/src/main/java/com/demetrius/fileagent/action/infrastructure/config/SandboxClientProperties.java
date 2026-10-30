package com.demetrius.fileagent.action.infrastructure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;

/**
 * 主应用访问独立 Sandbox Worker 的配置，只允许 loopback HTTP 地址并要求显式 Token。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
@Getter
@Setter
@Component
@ConditionalOnProperty(prefix = "fileagent.sandbox", name = "enabled", havingValue = "true")
@ConfigurationProperties(prefix = "fileagent.sandbox")
public class SandboxClientProperties implements InitializingBean {

    private String baseUrl = "http://127.0.0.1:8090";

    private String token;

    private Duration callTimeout = Duration.ofSeconds(15);

    @Override
    public void afterPropertiesSet() {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("启用 Sandbox 时必须配置 FILEAGENT_SANDBOX_TOKEN");
        }
        workerUri();
        if (callTimeout == null || callTimeout.isNegative() || callTimeout.isZero()) {
            throw new IllegalStateException("Sandbox Worker callTimeout 必须大于零");
        }
    }

    /**
     * 解析且校验 Worker 的唯一访问地址，禁止 localhost DNS、HTTPS、路径和远端主机。
     */
    public URI workerUri() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Sandbox Worker baseUrl 不能为空");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Sandbox Worker baseUrl 不合法", e);
        }
        String host = uri.getHost();
        if (!"http".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath()))
                || !isLoopbackLiteral(host)) {
            throw new IllegalStateException("Sandbox Worker 只能使用 loopback HTTP baseUrl");
        }
        if (uri.getPort() == 0 || uri.getPort() < -1 || uri.getPort() > 65535) {
            throw new IllegalStateException("Sandbox Worker baseUrl 端口不合法");
        }
        return uri;
    }

    private static boolean isLoopbackLiteral(String host) {
        return "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host);
    }
}
