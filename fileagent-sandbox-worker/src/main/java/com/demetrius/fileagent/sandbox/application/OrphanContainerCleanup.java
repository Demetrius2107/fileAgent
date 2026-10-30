package com.demetrius.fileagent.sandbox.application;

import com.demetrius.fileagent.sandbox.infrastructure.config.SandboxWorkerProperties;
import com.demetrius.fileagent.sandbox.infrastructure.runtime.PodmanCommandRunner;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 回收 Worker 异常退出遗留的 Sandbox 容器，只匹配本系统标签、名称和恢复窗口。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
@Slf4j
@Component
@DependsOn("sandboxPodmanRuntimeValidator")
public class OrphanContainerCleanup {

    private static final String EXECUTION_LABEL = "com.demetrius.fileagent.execution-id";
    private static final Pattern CONTAINER_LINE = Pattern.compile(
            "^([0-9a-f]{12,64})\\t(fileagent-sandbox-[0-9a-fA-F-]{36})$");

    private final SandboxWorkerProperties properties;
    private final PodmanCommandRunner commandRunner;

    public OrphanContainerCleanup(SandboxWorkerProperties properties,
                                  PodmanCommandRunner commandRunner) {
        this.properties = properties;
        this.commandRunner = commandRunner;
    }

    @PostConstruct
    public void cleanupOnStartup() {
        cleanupExpiredContainers();
    }

    @Scheduled(fixedDelayString = "${fileagent.sandbox.worker.cleanup-interval:PT1M}")
    public void cleanupExpiredContainers() {
        Instant cutoff = Instant.now().minus(properties.getCleanupAfter());
        for (ContainerReference container : listKnownContainers()) {
            Instant createdAt = createdAt(container.id());
            if (createdAt != null && createdAt.isBefore(cutoff)) {
                remove(container.id());
            }
        }
    }

    private List<ContainerReference> listKnownContainers() {
        String output = stdout(List.of(
                "podman", "ps", "-a",
                "--filter", "label=" + EXECUTION_LABEL,
                "--format", "{{.ID}}\t{{.Names}}"));
        return output.lines()
                .map(CONTAINER_LINE::matcher)
                .filter(java.util.regex.Matcher::matches)
                .map(matcher -> new ContainerReference(matcher.group(1), matcher.group(2)))
                .toList();
    }

    private Instant createdAt(String containerId) {
        String value = stdout(List.of("podman", "inspect", "--format", "{{.Created}}", containerId)).trim();
        try {
            return value.isBlank() ? null : Instant.parse(value);
        } catch (RuntimeException e) {
            log.warn("无法解析 Sandbox 容器创建时间: containerId={}", containerId);
            return null;
        }
    }

    private void remove(String containerId) {
        try {
            Process process = commandRunner.start(List.of("podman", "rm", "-f", containerId));
            if (!process.waitFor(properties.getTerminationTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(properties.getTerminationTimeout().toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.warn("无法删除过期 Sandbox 容器: containerId={}", containerId);
        }
    }

    private String stdout(List<String> command) {
        try {
            Process process = commandRunner.start(command);
            InputStream input = process.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> copyBounded(input, output));
            if (!process.waitFor(properties.getTerminationTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(properties.getTerminationTimeout().toMillis(), TimeUnit.MILLISECONDS);
                closeQuietly(input);
                return "";
            }
            reader.join(Math.max(1L, properties.getTerminationTimeout().toMillis()));
            if (reader.isAlive()) {
                closeQuietly(input);
                return "";
            }
            return process.exitValue() == 0 ? output.toString(StandardCharsets.UTF_8) : "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (IOException e) {
            return "";
        }
    }

    private static void copyBounded(InputStream input, ByteArrayOutputStream output) {
        try (input) {
            byte[] buffer = new byte[4_096];
            int remaining = 64 * 1024;
            int read;
            while (remaining > 0 && (read = input.read(buffer, 0, Math.min(buffer.length, remaining))) != -1) {
                output.write(buffer, 0, read);
                remaining -= read;
            }
        } catch (IOException ignored) {
            // 清理任务无法读取 Podman 输出时保持失败关闭，不操作无法确认的容器。
        }
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // 进程退出后输入流可能已关闭。
        }
    }

    private record ContainerReference(String id, String name) {
    }
}
