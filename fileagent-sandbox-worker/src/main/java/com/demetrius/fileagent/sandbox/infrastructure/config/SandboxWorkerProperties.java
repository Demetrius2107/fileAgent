package com.demetrius.fileagent.sandbox.infrastructure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Locale;

/**
 * Sandbox Worker 的本地监听和认证配置，禁止把 Worker 暴露到非 loopback 地址。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "fileagent.sandbox.worker")
public class SandboxWorkerProperties implements InitializingBean {

    private String token;

    private String bindAddress = "127.0.0.1";

    private Duration executionTimeout = Duration.ofSeconds(10);

    private Duration cleanupAfter = Duration.ofMinutes(2);

    private Duration cleanupInterval = Duration.ofMinutes(1);

    /** 本地构建后由部署方固定写入的 Podman 内容寻址镜像 ID，禁止运行可变 tag。 */
    private String imageId;

    /** 请求超时后等待 Podman 客户端和容器停止的二次上限，避免 Worker 线程无限阻塞。 */
    private Duration terminationTimeout = Duration.ofSeconds(2);

    @Override
    public void afterPropertiesSet() {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("启动 Sandbox Worker 时必须配置 FILEAGENT_SANDBOX_TOKEN");
        }
        resolveLoopbackAddress();
        if (executionTimeout == null || executionTimeout.isNegative() || executionTimeout.isZero()) {
            throw new IllegalStateException("Sandbox Worker executionTimeout 必须大于零");
        }
        if (cleanupAfter == null || cleanupAfter.isNegative() || cleanupAfter.isZero()) {
            throw new IllegalStateException("Sandbox Worker cleanupAfter 必须大于零");
        }
        if (cleanupInterval == null || cleanupInterval.isNegative() || cleanupInterval.isZero()) {
            throw new IllegalStateException("Sandbox Worker cleanupInterval 必须大于零");
        }
        immutableImageId();
        if (terminationTimeout == null || terminationTimeout.isNegative() || terminationTimeout.isZero()) {
            throw new IllegalStateException("Sandbox Worker terminationTimeout 必须大于零");
        }
    }

    /**
     * 解析经过白名单验证的监听地址，供 Web Server 工厂强制使用，不能由 server.address 绕过。
     */
    public InetAddress resolveLoopbackAddress() {
        if (bindAddress == null || bindAddress.isBlank()) {
            throw new IllegalStateException("Sandbox Worker bindAddress 不能为空");
        }
        try {
            InetAddress address = InetAddress.getByName(bindAddress);
            if (!address.isLoopbackAddress()) {
                throw new IllegalStateException("Sandbox Worker 只能绑定 loopback 地址");
            }
            return address;
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Sandbox Worker bindAddress 无法解析", e);
        }
    }

    /**
     * 返回经过格式校验的本地 Podman 内容 ID；运行时直接使用它，避免同名 tag 漂移到其他镜像。
     */
    public String immutableImageId() {
        if (imageId == null || !imageId.matches("(?i)^sha256:[0-9a-f]{64}$")) {
            throw new IllegalStateException("Sandbox Worker 必须配置 sha256 格式的 imageId");
        }
        return imageId.toLowerCase(Locale.ROOT);
    }
}
