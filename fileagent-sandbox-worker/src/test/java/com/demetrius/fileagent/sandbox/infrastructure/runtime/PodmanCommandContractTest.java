package com.demetrius.fileagent.sandbox.infrastructure.runtime;

import com.demetrius.fileagent.sandbox.infrastructure.config.SandboxWorkerProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Podman 命令安全契约测试：运行参数只由 Worker 固定，不接受主应用或模型覆盖。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
class PodmanCommandContractTest {

    private static final String EXECUTION_ID = "bf8c3fa4-7923-41d4-a64d-8c5edb421f6b";
    private static final String IMAGE_ID = "sha256:68c6b1ba2df9d414d9baeb2d6234e82ef68c41eb7eb20463363d6bcddedaaa37";

    @Test
    void shouldBuildFixedRootlessIsolationCommandWithoutShell() {
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setToken("sandbox-test-token");
        properties.setImageId(IMAGE_ID);
        PodmanSandboxContainerRuntime runtime = new PodmanSandboxContainerRuntime(
                properties, new ObjectMapper(), command -> {
                    throw new AssertionError("命令契约测试不应启动真实进程");
                });

        List<String> command = runtime.buildRunCommand(EXECUTION_ID);

        assertThat(command).containsExactly(
                "podman", "run", "-i",
                "--name", "fileagent-sandbox-" + EXECUTION_ID,
                "--label", "com.demetrius.fileagent.execution-id=" + EXECUTION_ID,
                "--network=none",
                "--http-proxy=false",
                "--read-only",
                "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m",
                "--cpus=1",
                "--memory=256m",
                "--pids-limit=64",
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges",
                "--user=65532:65532",
                "--pull=never",
                IMAGE_ID);
        assertThat(command).doesNotContain("sh", "bash", "-c", "--privileged", "--volume", "--mount");
    }
}
