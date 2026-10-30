package com.demetrius.fileagent.sandbox.application;

import com.demetrius.fileagent.sandbox.infrastructure.config.SandboxWorkerProperties;
import com.demetrius.fileagent.sandbox.infrastructure.runtime.PodmanCommandRunner;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 孤儿容器清理测试：只能操作本系统创建且超过恢复窗口的容器。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
class OrphanContainerCleanupTest {

    @Test
    void shouldRemoveOnlyExpiredContainersWithExpectedNameAndLabel() {
        String expiredId = "0123456789ab";
        String currentId = "abcdef012345";
        List<List<String>> commands = new ArrayList<>();
        PodmanCommandRunner runner = command -> {
            commands.add(List.copyOf(command));
            if (command.get(1).equals("ps")) {
                return completed(String.join("\n",
                        expiredId + "\tfileagent-sandbox-bf8c3fa4-7923-41d4-a64d-8c5edb421f6b",
                        currentId + "\tfileagent-sandbox-f58cf84d-c0fe-4adf-b20d-7a7a15467fb5",
                        "001122334455\tunrelated-container") + "\n");
            }
            if (command.get(1).equals("inspect")) {
                return completed(command.getLast().equals(expiredId)
                        ? Instant.now().minus(Duration.ofMinutes(3)) + "\n"
                        : Instant.now().toString() + "\n");
            }
            return completed("");
        };
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setToken("sandbox-test-token");
        OrphanContainerCleanup cleanup = new OrphanContainerCleanup(properties, runner);

        cleanup.cleanupExpiredContainers();

        assertThat(commands).contains(List.of("podman", "rm", "-f", expiredId));
        assertThat(commands).doesNotContain(List.of("podman", "rm", "-f", currentId));
        assertThat(commands).noneMatch(command -> command.contains("unrelated-container")
                || command.contains("sh") || command.contains("-c"));
    }

    private static Process completed(String stdout) {
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
                return 0;
            }

            @Override
            public int exitValue() {
                return 0;
            }

            @Override
            public void destroy() {
            }
        };
    }
}
