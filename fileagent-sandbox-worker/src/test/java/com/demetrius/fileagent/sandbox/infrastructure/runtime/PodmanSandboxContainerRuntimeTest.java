package com.demetrius.fileagent.sandbox.infrastructure.runtime;

import com.demetrius.fileagent.api.dto.sandbox.SandboxExecutionRequest;
import com.demetrius.fileagent.api.dto.sandbox.SandboxExecutionResponse;
import com.demetrius.fileagent.sandbox.infrastructure.config.SandboxWorkerProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Podman 运行时的超时收敛测试，确保异常子进程不能让 Worker 无限等待。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
class PodmanSandboxContainerRuntimeTest {

    private static final String EXECUTION_ID = "bf8c3fa4-7923-41d4-a64d-8c5edb421f6b";
    private static final String IMAGE_ID = "sha256:68c6b1ba2df9d414d9baeb2d6234e82ef68c41eb7eb20463363d6bcddedaaa37";

    @Test
    void shouldReturnBoundedTimeoutAfterStoppingUnresponsivePodmanProcess() {
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setToken("sandbox-test-token");
        properties.setImageId(IMAGE_ID);
        properties.setExecutionTimeout(Duration.ofMillis(1));
        properties.setTerminationTimeout(Duration.ofMillis(1));
        AtomicInteger starts = new AtomicInteger();
        PodmanCommandRunner runner = command -> starts.getAndIncrement() == 0
                ? new TimedOutProcess()
                : completedProcess();
        PodmanSandboxContainerRuntime runtime = new PodmanSandboxContainerRuntime(
                properties, new ObjectMapper(), runner);

        SandboxExecutionResponse response = runtime.execute(new SandboxExecutionRequest(
                EXECUTION_ID, "print(input_data['x'])", "{\"x\":3}"));

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.errorCode()).isEqualTo("SANDBOX_TIMEOUT");
        assertThat(starts).hasValue(2);
    }

    private static Process completedProcess() {
        return new Process() {
            @Override
            public OutputStream getOutputStream() {
                return new ByteArrayOutputStream();
            }

            @Override
            public InputStream getInputStream() {
                return InputStream.nullInputStream();
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

    private static final class TimedOutProcess extends Process {

        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        private volatile boolean stopped;

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return stopped;
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return stopped ? 0 : 1;
        }

        @Override
        public void destroy() {
            stopped = true;
        }
    }
}
