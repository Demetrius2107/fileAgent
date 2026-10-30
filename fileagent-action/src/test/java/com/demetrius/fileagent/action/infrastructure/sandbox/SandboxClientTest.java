package com.demetrius.fileagent.action.infrastructure.sandbox;

import com.demetrius.fileagent.action.infrastructure.config.SandboxClientProperties;
import com.demetrius.fileagent.api.dto.sandbox.SandboxExecutionResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 主应用到 Sandbox Worker 的 loopback 客户端测试，防止运行边界被请求参数或网络配置绕过。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
class SandboxClientTest {

    private static final String TOKEN = "sandbox-test-token";
    private static final String EXECUTION_ID = "bf8c3fa4-7923-41d4-a64d-8c5edb421f6b";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> receivedAuthorization = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/internal/sandbox/executions", this::respond);
        server.start();
        responseBody.set(successResponse());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldSendOnlyFixedRequestFieldsAndBearerTokenToLoopbackWorker() throws Exception {
        SandboxExecutionResponse response = client().execute(
                EXECUTION_ID, "print(input_data['x'])", "{\"x\":3}");

        assertThat(response.status()).isEqualTo("SUCCEEDED");
        assertThat(receivedAuthorization).hasValue("Bearer " + TOKEN);
        JsonNode request = objectMapper.readTree(receivedBody.get());
        assertThat(request.properties().stream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("executionId", "code", "inputJson");
        assertThat(request.get("executionId").asString()).isEqualTo(EXECUTION_ID);
        assertThat(request.get("code").asString()).isEqualTo("print(input_data['x'])");
        assertThat(request.get("inputJson").asString()).isEqualTo("{\"x\":3}");
    }

    @Test
    void shouldResolveFixedWorkerPathWhenBaseUrlHasTrailingSlash() {
        SandboxExecutionResponse response = client("http://127.0.0.1:" + server.getAddress().getPort() + "/")
                .execute(EXECUTION_ID, "print(1)", "{}");

        assertThat(response.status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void shouldRejectNonLoopbackWorkerBeforeSendingAnyRequest() {
        SandboxClientProperties properties = properties("https://example.com");

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void shouldMapWorkerAndTransportFailuresToStableCodes() {
        responseStatus.set(503);
        SandboxExecutionResponse httpFailure = client().execute(EXECUTION_ID, "print(1)", "{}");
        assertThat(httpFailure.status()).isEqualTo("FAILED");
        assertThat(httpFailure.errorCode()).isEqualTo("SANDBOX_WORKER_HTTP_503");

        server.stop(0);
        SandboxExecutionResponse transportFailure = client().execute(EXECUTION_ID, "print(1)", "{}");
        assertThat(transportFailure.status()).isEqualTo("FAILED");
        assertThat(transportFailure.errorCode()).isEqualTo("SANDBOX_WORKER_UNAVAILABLE");
    }

    @Test
    void shouldPreserveStableWorkerFailureWithoutExposingProtocolDetails() {
        responseBody.set("""
                {"code":0,"message":"ok","data":{"status":"FAILED","stdout":"","stderr":"","errorCode":"SANDBOX_TIMEOUT"}}
                """);

        SandboxExecutionResponse response = client().execute(EXECUTION_ID, "while True: pass", "{}");

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.errorCode()).isEqualTo("SANDBOX_TIMEOUT");
    }

    private SandboxClient client() {
        return client("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private SandboxClient client(String baseUrl) {
        SandboxClientProperties properties = properties(baseUrl);
        properties.afterPropertiesSet();
        return new SandboxClient(properties, objectMapper);
    }

    private static SandboxClientProperties properties(String baseUrl) {
        SandboxClientProperties properties = new SandboxClientProperties();
        properties.setBaseUrl(baseUrl);
        properties.setToken(TOKEN);
        return properties;
    }

    private void respond(HttpExchange exchange) throws IOException {
        receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus.get(), body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static String successResponse() {
        return """
                {"code":0,"message":"ok","data":{"status":"SUCCEEDED","stdout":"3\\n","stderr":"","errorCode":null}}
                """;
    }
}
