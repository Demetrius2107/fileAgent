package com.demetrius.fileagent.sandbox.infrastructure.runtime;

import com.demetrius.fileagent.sandbox.infrastructure.config.SandboxWorkerProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Worker 启动前验证 rootless Podman 和已配置的不可变镜像 ID，任一不满足时拒绝提供执行能力。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
@Component("sandboxPodmanRuntimeValidator")
public class SandboxPodmanRuntimeValidator implements InitializingBean {

    private static final int MAX_COMMAND_OUTPUT_BYTES = 4 * 1024;

    private final SandboxWorkerProperties properties;
    private final PodmanCommandRunner commandRunner;

    public SandboxPodmanRuntimeValidator(SandboxWorkerProperties properties,
                                         PodmanCommandRunner commandRunner) {
        this.properties = properties;
        this.commandRunner = commandRunner;
    }

    @Override
    public void afterPropertiesSet() {
        String rootless = runAndRead(List.of("podman", "info", "--format", "{{.Host.Security.Rootless}}"));
        if (!"true".equalsIgnoreCase(rootless.strip())) {
            throw new IllegalStateException("Sandbox Worker 只能使用 rootless Podman");
        }
        String expected = properties.immutableImageId();
        String actual = normalizeImageId(runAndRead(List.of(
                "podman", "image", "inspect", "--format", "{{.Id}}", expected)));
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Sandbox Worker 配置的 imageId 与本地 Podman 镜像不一致");
        }
    }

    private String runAndRead(List<String> command) {
        Process process;
        try {
            process = commandRunner.start(command);
        } catch (IOException e) {
            throw new IllegalStateException("Sandbox Worker 无法启动 Podman 校验", e);
        }
        InputStream stdout = process.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> copyBounded(stdout, output));
        try {
            if (!process.waitFor(properties.getTerminationTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(properties.getTerminationTimeout().toMillis(), TimeUnit.MILLISECONDS);
                throw new IllegalStateException("Sandbox Worker Podman 校验超时");
            }
            reader.join(Math.max(1L, properties.getTerminationTimeout().toMillis()));
            if (reader.isAlive()) {
                closeQuietly(stdout);
                throw new IllegalStateException("Sandbox Worker 无法在限定时间读取 Podman 校验结果");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Sandbox Worker Podman 校验失败");
            }
            return output.toString(StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("Sandbox Worker Podman 校验被中断", e);
        } finally {
            closeQuietly(stdout);
        }
    }

    private static void copyBounded(InputStream input, ByteArrayOutputStream output) {
        try (input) {
            byte[] buffer = new byte[1_024];
            int remaining = MAX_COMMAND_OUTPUT_BYTES;
            int read;
            while (remaining > 0 && (read = input.read(buffer, 0, Math.min(buffer.length, remaining))) != -1) {
                output.write(buffer, 0, read);
                remaining -= read;
            }
        } catch (IOException ignored) {
            // 外部命令输出异常会在后续结果校验中失败关闭。
        }
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // 进程结束时流通常已关闭。
        }
    }

    private static String normalizeImageId(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return normalized.startsWith("sha256:") ? normalized : "sha256:" + normalized;
    }
}
