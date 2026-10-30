package com.demetrius.fileagent.sandbox.infrastructure.runtime;

import com.demetrius.fileagent.sandbox.infrastructure.config.SandboxWorkerProperties;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Podman 启动校验测试：Worker 必须验证 rootless 模式和配置的内容寻址镜像 ID。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
class SandboxPodmanRuntimeValidatorTest {

    private static final String IMAGE_ID = "sha256:68c6b1ba2df9d414d9baeb2d6234e82ef68c41eb7eb20463363d6bcddedaaa37";

    @Test
    void shouldAcceptOnlyRootlessPodmanWithTheConfiguredImmutableImage() {
        SandboxWorkerProperties properties = properties();
        List<List<String>> commands = new ArrayList<>();
        PodmanCommandRunner runner = command -> {
            commands.add(List.copyOf(command));
            if (command.get(1).equals("info")) {
                return completed("true\n", 0);
            }
            return completed(IMAGE_ID.substring("sha256:".length()) + "\n", 0);
        };

        new SandboxPodmanRuntimeValidator(properties, runner).afterPropertiesSet();

        assertThat(commands).containsExactly(
                List.of("podman", "info", "--format", "{{.Host.Security.Rootless}}"),
                List.of("podman", "image", "inspect", "--format", "{{.Id}}", IMAGE_ID));
    }

    @Test
    void shouldFailClosedWhenPodmanIsNotRootless() {
        SandboxWorkerProperties properties = properties();
        PodmanCommandRunner runner = command -> completed("false\n", 0);

        assertThatThrownBy(() -> new SandboxPodmanRuntimeValidator(properties, runner).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rootless");
    }

    @Test
    void shouldFailClosedWhenConfiguredImageDoesNotMatchLocalImage() {
        SandboxWorkerProperties properties = properties();
        PodmanCommandRunner runner = command -> command.get(1).equals("info")
                ? completed("true\n", 0)
                : completed("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n", 0);

        assertThatThrownBy(() -> new SandboxPodmanRuntimeValidator(properties, runner).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("imageId");
    }

    private static SandboxWorkerProperties properties() {
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setToken("sandbox-test-token");
        properties.setImageId(IMAGE_ID);
        return properties;
    }

    private static Process completed(String stdout, int exitCode) {
        return new Process() {
            @Override
            public OutputStream getOutputStream() {
                return new ByteArrayOutputStream();
            }

            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public InputStream getErrorStream() {
                return InputStream.nullInputStream();
            }

            @Override
            public int waitFor() {
                return exitCode;
            }

            @Override
            public int exitValue() {
                return exitCode;
            }

            @Override
            public void destroy() {
            }
        };
    }
}
