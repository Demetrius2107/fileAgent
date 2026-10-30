package com.demetrius.fileagent.sandbox.infrastructure.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.stereotype.Component;

/**
 * 强制 Worker 使用经过 loopback 校验的地址，防止外部 server.address 配置意外暴露内部接口。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
@Component
@RequiredArgsConstructor
public class SandboxWorkerWebServerCustomizer
        implements WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> {

    private final SandboxWorkerProperties properties;

    @Override
    public void customize(ConfigurableServletWebServerFactory factory) {
        factory.setAddress(properties.resolveLoopbackAddress());
    }
}
