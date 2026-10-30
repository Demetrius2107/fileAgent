package com.demetrius.fileagent.sandbox.infrastructure.runtime;

import com.demetrius.fileagent.api.dto.sandbox.SandboxExecutionRequest;
import com.demetrius.fileagent.api.dto.sandbox.SandboxExecutionResponse;
import com.demetrius.fileagent.sandbox.infrastructure.config.SandboxWorkerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 真实容器隔离门禁：只验证声明的容器边界，不把它表述为 Python 语言级或绝对逃逸防护。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
class Phase3SandboxIsolationSecurityTest {

    private static final String IMAGE_ID_PATTERN = "(?i)^sha256:[0-9a-f]{64}$";
    private static final String PROXY_SENTINEL = "http://phase3-security-proxy.invalid:18080";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PodmanSandboxContainerRuntime runtime;

    @BeforeEach
    void setUp() {
        assertThat(System.getProperty("fileagent.phase3.security.live"))
                .as("真实容器测试只能通过 phase3-security profile 运行")
                .isEqualTo("true");
        String imageId = System.getenv("FILEAGENT_SANDBOX_IMAGE_ID");
        assertThat(imageId)
                .as("安全门禁必须由脚本传入本地固定镜像 ID")
                .matches(IMAGE_ID_PATTERN);
        assertThat(System.getenv("HTTP_PROXY"))
                .as("安全门禁脚本必须注入代理哨兵值，验证 Podman 不会传给容器")
                .isEqualTo(PROXY_SENTINEL);

        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setToken("phase3-security-test-token");
        properties.setImageId(imageId);
        properties.setExecutionTimeout(Duration.ofSeconds(1));
        properties.setTerminationTimeout(Duration.ofSeconds(2));
        properties.afterPropertiesSet();
        runtime = new PodmanSandboxContainerRuntime(properties, objectMapper,
                new ProcessBuilderPodmanCommandRunner());
    }

    @Test
    void shouldBlockNetworkAndHideHostCanary() throws Exception {
        SandboxExecutionResponse network = execute("""
                import socket
                socket.getaddrinfo('example.com', 443)
                print('network unexpectedly available')
                """);

        assertThat(network.status()).isEqualTo("FAILED");
        assertThat(network.errorCode()).isEqualTo("PYTHON_RUNTIME_ERROR");

        Path hostCanary = Files.createTempFile("fileagent-phase3-host-canary-", ".txt");
        Files.writeString(hostCanary, "host-only");
        try {
            String code = "from pathlib import Path\nprint(Path("
                    + objectMapper.writeValueAsString(hostCanary.toString()) + ").exists())";

            SandboxExecutionResponse hostRead = execute(code);

            assertThat(hostRead.status()).isEqualTo("SUCCEEDED");
            assertThat(hostRead.stdout()).isEqualTo("False\n");
        } finally {
            Files.deleteIfExists(hostCanary);
        }
    }

    @Test
    void shouldEnforceOutputAndExecutionTimeLimits() {
        SandboxExecutionResponse oversizedOutput = execute("print('x' * 70000)");

        assertThat(oversizedOutput.status()).isEqualTo("FAILED");
        assertThat(oversizedOutput.errorCode()).isEqualTo("OUTPUT_LIMIT_EXCEEDED");

        Instant startedAt = Instant.now();
        SandboxExecutionResponse timeout = execute("while True: pass");

        assertThat(timeout.status()).isEqualTo("FAILED");
        assertThat(timeout.errorCode()).isEqualTo("SANDBOX_TIMEOUT");
        assertThat(Duration.between(startedAt, Instant.now())).isLessThan(Duration.ofSeconds(8));
    }

    @Test
    void shouldNotInheritHostProxyVariables() {
        SandboxExecutionResponse response = execute("""
                import os
                keys = ('HTTP_PROXY', 'HTTPS_PROXY', 'NO_PROXY', 'http_proxy', 'https_proxy', 'no_proxy')
                print('|'.join(str(os.environ.get(key)) for key in keys))
                """);

        assertThat(response.status()).isEqualTo("SUCCEEDED");
        assertThat(response.stdout()).isEqualTo("None|None|None|None|None|None\n");
    }

    private SandboxExecutionResponse execute(String code) {
        return runtime.execute(new SandboxExecutionRequest(
                UUID.randomUUID().toString(), code, "{}"));
    }
}
