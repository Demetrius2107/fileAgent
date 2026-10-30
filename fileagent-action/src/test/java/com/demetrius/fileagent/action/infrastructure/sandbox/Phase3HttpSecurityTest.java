package com.demetrius.fileagent.action.infrastructure.sandbox;

import com.demetrius.fileagent.action.infrastructure.config.SandboxClientProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3 Worker HTTP 边界门禁：主应用只能向固定 loopback 根地址发送 Sandbox 请求。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
class Phase3HttpSecurityTest {

    @Test
    void shouldRejectRemoteWorkerAndArbitraryBasePath() {
        SandboxClientProperties remote = properties("http://169.254.169.254:8090");
        assertThatThrownBy(remote::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("loopback");

        SandboxClientProperties arbitraryPath = properties("http://127.0.0.1:8090/attacker-path");
        assertThatThrownBy(arbitraryPath::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("loopback");
    }

    private static SandboxClientProperties properties(String baseUrl) {
        SandboxClientProperties properties = new SandboxClientProperties();
        properties.setToken("phase3-security-test-token");
        properties.setBaseUrl(baseUrl);
        return properties;
    }
}
