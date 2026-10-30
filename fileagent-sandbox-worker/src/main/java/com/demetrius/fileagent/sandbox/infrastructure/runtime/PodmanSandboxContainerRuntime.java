package com.demetrius.fileagent.sandbox.infrastructure.runtime;

import com.demetrius.fileagent.api.dto.sandbox.SandboxExecutionRequest;
import com.demetrius.fileagent.api.dto.sandbox.SandboxExecutionResponse;
import com.demetrius.fileagent.sandbox.application.SandboxContainerRuntime;
import com.demetrius.fileagent.sandbox.infrastructure.config.SandboxWorkerProperties;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Rootless Podman 一次性容器运行时，镜像、网络、挂载、身份与资源限制均由 Worker 固定。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
@Component
@DependsOn("sandboxPodmanRuntimeValidator")
public class PodmanSandboxContainerRuntime implements SandboxContainerRuntime {

    private static final String CONTAINER_PREFIX = "fileagent-sandbox-";
    private static final String EXECUTION_LABEL = "com.demetrius.fileagent.execution-id";
    private static final int MAX_PROCESS_OUTPUT_BYTES = 64 * 1024;
    private static final Set<String> RESULT_STATUSES = Set.of("SUCCEEDED", "FAILED");

    private final SandboxWorkerProperties properties;
    private final ObjectMapper objectMapper;
    private final PodmanCommandRunner commandRunner;

    public PodmanSandboxContainerRuntime(SandboxWorkerProperties properties,
                                         ObjectMapper objectMapper,
                                         PodmanCommandRunner commandRunner) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.commandRunner = commandRunner;
    }

    @Override
    public SandboxExecutionResponse execute(SandboxExecutionRequest request) {
        if (request == null || !validExecutionId(request.executionId())) {
            return failed("SANDBOX_INVALID_EXECUTION_ID");
        }
        if (request.code() == null || request.code().isBlank() || request.inputJson() == null || request.inputJson().isBlank()) {
            return failed("SANDBOX_INVALID_REQUEST");
        }
        JsonNode input;
        try {
            input = objectMapper.readTree(request.inputJson());
        } catch (Exception e) {
            return failed("SANDBOX_INVALID_INPUT_JSON");
        }
        if (input == null || !input.isObject()) {
            return failed("SANDBOX_INVALID_INPUT_JSON");
        }

        String containerName = CONTAINER_PREFIX + request.executionId();
        Process process;
        try {
            process = commandRunner.start(buildRunCommand(request.executionId()));
        } catch (IOException e) {
            return failed("SANDBOX_RUNTIME_UNAVAILABLE");
        }
        OutputCapture stdout = OutputCapture.start(process.getInputStream());
        OutputCapture stderr = OutputCapture.start(process.getErrorStream());
        try {
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(objectMapper.writeValueAsBytes(Map.of("code", request.code(), "input", input)));
            }
            boolean completed = process.waitFor(properties.getExecutionTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                boolean terminated = terminate(process);
                awaitOutputCaptures(stdout, stderr);
                return failed(terminated ? "SANDBOX_TIMEOUT" : "SANDBOX_TERMINATION_UNCONFIRMED");
            }
            if (!awaitOutputCaptures(stdout, stderr)) {
                return failed("SANDBOX_OUTPUT_DRAIN_TIMEOUT");
            }
            if (stdout.overflow() || stderr.overflow()) {
                return failed("SANDBOX_OUTPUT_LIMIT_EXCEEDED");
            }
            if (process.exitValue() != 0) {
                return failed("SANDBOX_RUNTIME_FAILURE");
            }
            return parseRunnerResponse(stdout.text());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return failed("SANDBOX_RUNTIME_INTERRUPTED");
        } catch (IOException e) {
            terminateQuietly(process);
            return failed("SANDBOX_RUNTIME_FAILURE");
        } finally {
            stdout.close();
            stderr.close();
            removeContainer(containerName);
        }
    }

    /**
     * 返回不可由请求内容影响的 Podman 参数列表，供执行和安全契约测试共用。
     */
    List<String> buildRunCommand(String executionId) {
        if (!validExecutionId(executionId)) {
            throw new IllegalArgumentException("executionId 必须是 UUID");
        }
        String containerName = CONTAINER_PREFIX + executionId;
        return List.of(
                "podman", "run",
                "-i",
                "--name", containerName,
                "--label", EXECUTION_LABEL + "=" + executionId,
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
                properties.immutableImageId());
    }

    private SandboxExecutionResponse parseRunnerResponse(String stdout) {
        try {
            SandboxExecutionResponse response = objectMapper.readValue(stdout, SandboxExecutionResponse.class);
            if (response == null || !RESULT_STATUSES.contains(response.status())
                    || response.stdout() == null || response.stderr() == null) {
                return failed("SANDBOX_INVALID_RUNNER_RESPONSE");
            }
            return response;
        } catch (Exception e) {
            return failed("SANDBOX_INVALID_RUNNER_RESPONSE");
        }
    }

    private void removeContainer(String containerName) {
        try {
            Process cleanup = commandRunner.start(List.of("podman", "rm", "-f", containerName));
            if (!cleanup.waitFor(properties.getTerminationTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                cleanup.destroyForcibly();
                cleanup.waitFor(properties.getTerminationTimeout().toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // 容器自身在退出时可能已经消失；周期清理会处理剩余孤儿。
        }
    }

    private boolean awaitOutputCaptures(OutputCapture stdout, OutputCapture stderr) throws InterruptedException {
        boolean stdoutComplete = stdout.await(properties.getTerminationTimeout());
        boolean stderrComplete = stderr.await(properties.getTerminationTimeout());
        return stdoutComplete && stderrComplete;
    }

    private boolean terminate(Process process) throws InterruptedException {
        process.destroy();
        if (process.waitFor(properties.getTerminationTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
            return true;
        }
        process.destroyForcibly();
        return process.waitFor(properties.getTerminationTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void terminateQuietly(Process process) {
        try {
            terminate(process);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean validExecutionId(String executionId) {
        try {
            UUID.fromString(executionId);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static SandboxExecutionResponse failed(String errorCode) {
        return new SandboxExecutionResponse("FAILED", "", "", errorCode);
    }

    private static final class OutputCapture {

        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final InputStream input;
        private final Thread reader;
        private volatile boolean overflow;

        private OutputCapture(InputStream input) {
            this.input = input;
            this.reader = Thread.ofVirtual().start(() -> read(input));
        }

        static OutputCapture start(InputStream input) {
            return new OutputCapture(input);
        }

        boolean await(Duration timeout) throws InterruptedException {
            long timeoutMillis = Math.max(1L, timeout.toMillis());
            reader.join(timeoutMillis);
            if (reader.isAlive()) {
                close();
                reader.join(timeoutMillis);
            }
            return !reader.isAlive();
        }

        boolean overflow() {
            return overflow;
        }

        String text() {
            return output.toString(StandardCharsets.UTF_8);
        }

        void close() {
            try {
                input.close();
            } catch (IOException ignored) {
                // 输入流已关闭时不影响容器清理。
            }
        }

        private void read(InputStream input) {
            try (input) {
                byte[] buffer = new byte[4_096];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    int remaining = MAX_PROCESS_OUTPUT_BYTES - output.size();
                    if (remaining <= 0) {
                        overflow = true;
                        continue;
                    }
                    int accepted = Math.min(remaining, read);
                    output.write(buffer, 0, accepted);
                    if (accepted < read) {
                        overflow = true;
                    }
                }
            } catch (IOException ignored) {
                overflow = true;
            }
        }
    }
}
