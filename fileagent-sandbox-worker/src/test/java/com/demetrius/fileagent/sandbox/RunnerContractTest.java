package com.demetrius.fileagent.sandbox;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 固定 Python Runner 的 stdin/stdout 协议测试，异常路径不得把 Python traceback 交给上层。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
class RunnerContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldReturnOnlyStructuredJsonForSuccessfulExecution() throws Exception {
        RunnerResult result = run(Map.of(
                "code", "print(input_data['x'])",
                "input", Map.of("x", 3)));

        assertThat(result.exitCode()).isZero();
        assertThat(result.processStderr()).isBlank();
        JsonNode response = objectMapper.readTree(result.stdout());
        assertThat(response.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(response.get("stdout").asString()).isEqualTo("3\n");
        assertThat(response.get("stderr").asString()).isBlank();
        assertThat(response.get("errorCode").isNull()).isTrue();
    }

    @Test
    void shouldReturnStableSyntaxAndRuntimeErrorsWithoutTraceback() throws Exception {
        RunnerResult syntaxError = run(Map.of(
                "code", "if True print('broken')",
                "input", Map.of()));
        JsonNode syntaxResponse = objectMapper.readTree(syntaxError.stdout());
        assertThat(syntaxError.exitCode()).isZero();
        assertThat(syntaxResponse.get("status").asString()).isEqualTo("FAILED");
        assertThat(syntaxResponse.get("errorCode").asString()).isEqualTo("PYTHON_SYNTAX_ERROR");
        assertThat(syntaxError.stdout()).doesNotContain("Traceback", "broken");

        RunnerResult runtimeError = run(Map.of(
                "code", "raise RuntimeError('private diagnostic')",
                "input", Map.of()));
        JsonNode runtimeResponse = objectMapper.readTree(runtimeError.stdout());
        assertThat(runtimeError.exitCode()).isZero();
        assertThat(runtimeResponse.get("status").asString()).isEqualTo("FAILED");
        assertThat(runtimeResponse.get("errorCode").asString()).isEqualTo("PYTHON_RUNTIME_ERROR");
        assertThat(runtimeError.stdout()).doesNotContain("Traceback", "private diagnostic");
    }

    @Test
    void shouldRejectOutputBeyondLimitWithoutReturningPartialPayload() throws Exception {
        RunnerResult result = run(Map.of(
                "code", "print('x' * 70000)",
                "input", Map.of()));

        JsonNode response = objectMapper.readTree(result.stdout());
        assertThat(result.exitCode()).isZero();
        assertThat(response.get("status").asString()).isEqualTo("FAILED");
        assertThat(response.get("errorCode").asString()).isEqualTo("OUTPUT_LIMIT_EXCEEDED");
        assertThat(response.get("stdout").asString()).isBlank();
        assertThat(response.get("stderr").asString()).isBlank();
        assertThat(result.stdout().getBytes(StandardCharsets.UTF_8)).hasSizeLessThan(1_024);
    }

    @Test
    void shouldAbortManySmallWritesAtTheOutputLimitInsteadOfAccumulatingDiscardedChunks() throws Exception {
        RunnerResult result = run(Map.of(
                "code", "for _ in range(100000): print('x')",
                "input", Map.of()));

        JsonNode response = objectMapper.readTree(result.stdout());
        assertThat(result.exitCode()).isZero();
        assertThat(response.get("status").asString()).isEqualTo("FAILED");
        assertThat(response.get("errorCode").asString()).isEqualTo("OUTPUT_LIMIT_EXCEEDED");
        assertThat(response.get("stdout").asString()).isBlank();
        assertThat(response.get("stderr").asString()).isBlank();
    }

    @Test
    void shouldPinImageDigestAndFixedRunnerEntrypoint() throws Exception {
        Path dockerfile = Path.of(System.getProperty("user.dir"))
                .resolve("../deploy/sandbox/Dockerfile")
                .normalize();
        assertThat(Files.isRegularFile(dockerfile)).isTrue();

        String content = Files.readString(dockerfile);
        assertThat(content).contains(
                "FROM docker.io/library/python@sha256:b2bf8fba2b1ccfc91c736c8dd80c4218a5c165d547d8983b7c5a47f3472fe167",
                "USER 65532:65532",
                "ENTRYPOINT [\"python3\", \"-I\", \"-S\", \"/opt/fileagent/runner.py\"]")
                .doesNotContain("python:3.13-slim", "latest");
    }

    private RunnerResult run(Map<String, Object> request) throws Exception {
        Path runner = runnerPath();
        Process process = new ProcessBuilder("python3", runner.toString()).start();
        try (var stdin = process.getOutputStream()) {
            stdin.write(objectMapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8));
        }
        assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        return new RunnerResult(
                process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static Path runnerPath() throws IOException {
        Path runner = Path.of(System.getProperty("user.dir"))
                .resolve("../deploy/sandbox/runner.py")
                .normalize();
        assertThat(Files.isRegularFile(runner)).isTrue();
        return runner;
    }

    private record RunnerResult(int exitCode, String stdout, String processStderr) {
    }
}
