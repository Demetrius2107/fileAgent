package com.demetrius.fileagent.action.infrastructure.http;

import com.demetrius.fileagent.action.infrastructure.config.RegisteredHttpActionProperties;
import com.demetrius.fileagent.action.infrastructure.config.RegisteredHttpActionProperties.DataEgressPolicy;
import com.demetrius.fileagent.action.infrastructure.config.RegisteredHttpActionProperties.Endpoint;
import com.demetrius.fileagent.api.enums.ActionCancelStatus;
import com.demetrius.fileagent.api.enums.ActionExecutionStatus;
import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import com.demetrius.fileagent.common.security.EnvelopeCrypto;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.Dns;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 受限 HTTP 执行器的传输边界、超限和取消语义测试。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-20
 */
class RegisteredHttpExecutorTest {

    private HttpServer server;
    private CountDownLatch slowRequestStarted;
    private AtomicReference<String> receivedOperationId;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        slowRequestStarted = new CountDownLatch(1);
        receivedOperationId = new AtomicReference<>();
        server.createContext("/success", exchange -> {
            receivedOperationId.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            respond(exchange, 200, "application/json", "{\"draftId\":\"draft-1\"}");
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/success");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/large", exchange ->
                respond(exchange, 200, "application/json", "x".repeat(512)));
        server.createContext("/wrong-content", exchange ->
                respond(exchange, 200, "application/octet-stream", "binary"));
        server.createContext("/large-headers", exchange -> {
            exchange.getResponseHeaders().set("X-Large", "x".repeat(20_000));
            respond(exchange, 200, "application/json", "{}");
        });
        server.createContext("/slow", exchange -> {
            slowRequestStarted.countDown();
            try {
                Thread.sleep(500);
                respond(exchange, 200, "application/json", "{}");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void shouldSendStableOperationIdAndReadBoundedResponse() {
        RegisteredHttpExecutor executor = executor("/success", 256, Duration.ofSeconds(2));

        RegisteredHttpExecutor.HttpExecutionResult result = executor.execute(
                "execution-1", "endpoint", "operation-1", Map.of());

        assertThat(result.status()).isEqualTo(ActionExecutionStatus.SUCCEEDED);
        assertThat(result.resultCode()).isEqualTo("HTTP_200");
        assertThat(result.body()).contains("draft-1");
        assertThat(receivedOperationId).hasValue("operation-1");
    }

    @Test
    void shouldRejectRedirectWrongContentLargeHeadersAndLargeBodyWithoutFollowingRedirect() {
        assertThat(executor("/redirect", 256, Duration.ofSeconds(2))
                .execute("execution-r", "endpoint", "operation-r", Map.of()).resultCode())
                .isEqualTo("HTTP_REDIRECT_REJECTED");

        RegisteredHttpExecutor.HttpExecutionResult wrongContent = executor(
                "/wrong-content", 256, Duration.ofSeconds(2))
                .execute("execution-c", "endpoint", "operation-c", Map.of());
        assertThat(wrongContent.status()).isEqualTo(ActionExecutionStatus.OUTCOME_UNKNOWN);
        assertThat(wrongContent.resultCode()).isEqualTo("HTTP_CONTENT_TYPE_REJECTED");

        RegisteredHttpExecutor.HttpExecutionResult largeHeaders = executor(
                "/large-headers", 256, Duration.ofSeconds(2))
                .execute("execution-h", "endpoint", "operation-h", Map.of());
        assertThat(largeHeaders.status()).isEqualTo(ActionExecutionStatus.OUTCOME_UNKNOWN);
        assertThat(largeHeaders.resultCode()).isEqualTo("HTTP_RESPONSE_HEADERS_TOO_LARGE");

        RegisteredHttpExecutor.HttpExecutionResult largeBody = executor(
                "/large", 64, Duration.ofSeconds(2))
                .execute("execution-b", "endpoint", "operation-b", Map.of());
        assertThat(largeBody.status()).isEqualTo(ActionExecutionStatus.OUTCOME_UNKNOWN);
        assertThat(largeBody.resultCode()).isEqualTo("HTTP_RESPONSE_TOO_LARGE");
        assertThat(receivedOperationId).doesNotHaveValue("operation-r");
    }

    @Test
    void shouldMarkTimeoutAndCancellationAfterSendAsOutcomeUnknown() throws Exception {
        RegisteredHttpExecutor timeoutExecutor = executor("/slow", 256, Duration.ofMillis(100));
        RegisteredHttpExecutor.HttpExecutionResult timeout = timeoutExecutor.execute(
                "execution-timeout", "endpoint", "operation-timeout", Map.of());
        assertThat(timeout.status()).isEqualTo(ActionExecutionStatus.OUTCOME_UNKNOWN);
        assertThat(timeout.resultCode()).isEqualTo("HTTP_OUTCOME_UNKNOWN");

        slowRequestStarted = new CountDownLatch(1);
        RegisteredHttpExecutor cancelExecutor = executor("/slow", 256, Duration.ofSeconds(2));
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<RegisteredHttpExecutor.HttpExecutionResult> running = pool.submit(() -> cancelExecutor.execute(
                    "execution-cancel", "endpoint", "operation-cancel", Map.of()));
            assertThat(slowRequestStarted.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(cancelExecutor.cancel("execution-cancel")).isEqualTo(ActionCancelStatus.OUTCOME_UNKNOWN);
            assertThat(running.get(2, TimeUnit.SECONDS).status()).isEqualTo(ActionExecutionStatus.OUTCOME_UNKNOWN);
        }
        assertThat(cancelExecutor.cancel("missing")).isEqualTo(ActionCancelStatus.NOT_STARTED);
    }

    private RegisteredHttpExecutor executor(String path, int maxResponseBytes, Duration readTimeout) {
        ObjectMapper objectMapper = new ObjectMapper();
        EnvelopeCrypto crypto = mock(EnvelopeCrypto.class);
        when(crypto.hmac(anyString())).thenAnswer(invocation -> "hmac:" + invocation.getArgument(0));
        RegisteredHttpActionProperties properties = new RegisteredHttpActionProperties();
        properties.setEnabled(true);
        properties.setEndpoints(List.of(endpoint(path, maxResponseBytes, readTimeout)));
        RegisteredHttpActionRegistry registry = RegisteredHttpActionRegistry.forTesting(
                properties, objectMapper, crypto);
        Dns loopbackDns = hostname -> List.of(InetAddress.getLoopbackAddress());
        BlockedAddressPolicy allowLoopback = new BlockedAddressPolicy() {
            @Override
            public boolean isBlocked(InetAddress address) {
                return false;
            }
        };
        return new RegisteredHttpExecutor(registry, objectMapper, new SafeHttpUriBuilder(),
                ValidatedDns.forTesting(loopbackDns, allowLoopback));
    }

    private Endpoint endpoint(String path, int maxResponseBytes, Duration readTimeout) {
        Endpoint endpoint = new Endpoint();
        endpoint.setEndpointId("endpoint");
        endpoint.setDisplayName("测试写动作");
        endpoint.setDefinitionVersion("definition-v1");
        endpoint.setEndpointRevision("endpoint-v1");
        endpoint.setPolicyVersion("policy-v1");
        endpoint.setMethod("POST");
        endpoint.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        endpoint.setPathTemplate(path);
        endpoint.setRequestSchema(Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(),
                "required", List.of()));
        endpoint.setRiskLevel(ActionRiskLevel.LOW_RISK_WRITE);
        DataEgressPolicy policy = new DataEgressPolicy();
        policy.setModelWritableFields(Set.of());
        endpoint.setDataEgressPolicy(policy);
        endpoint.setResourceScope("test:write");
        endpoint.setSafeSummary("执行测试写动作");
        endpoint.setExpectedImpact("测试服务将收到一次写请求");
        endpoint.setConnectTimeout(Duration.ofSeconds(1));
        endpoint.setReadTimeout(readTimeout);
        endpoint.setCallTimeout(Duration.ofSeconds(3));
        endpoint.setMaxResponseBytes(maxResponseBytes);
        endpoint.setAllowedContentTypes(Set.of("application/json"));
        endpoint.setIdempotencyHeader("Idempotency-Key");
        return endpoint;
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
