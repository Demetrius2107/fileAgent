package com.demetrius.ragflowquickstart;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RAGFlow 客户端 HTTP 路径、鉴权、上传解析编排及 SSE 解码测试。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-09-04
 */
class RagFlowClientTest {

    private HttpServer server;
    private RagFlowClient client;
    private final List<RecordedRequest> requests = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", this::handle);
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        client = new RagFlowClient(
                new RagFlowProperties(baseUrl, "test-key"),
                new ObjectMapper(),
                WebClient.builder());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void shouldCreateDatasetWithBearerToken() {
        JsonNode response = client.createDataset("manuals").block();

        assertThat(response.path("data").path("id").asString()).isEqualTo("dataset-1");
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/api/v1/datasets");
            assertThat(request.authorization()).isEqualTo("Bearer test-key");
            assertThat(request.body()).contains("\"name\":\"manuals\"");
        });
    }

    @Test
    void shouldUploadThenStartParsing() {
        FilePart file = mock(FilePart.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        when(file.filename()).thenReturn("guide.txt");
        when(file.headers()).thenReturn(headers);
        when(file.content()).thenReturn(Flux.just(
                DefaultDataBufferFactory.sharedInstance.wrap("hello".getBytes(StandardCharsets.UTF_8))));

        RagFlowClient.UploadResult result = client.uploadAndParse("dataset-1", Flux.just(file)).block();

        assertThat(result.parsingStarted()).isTrue();
        assertThat(result.documents().get(0).path("id").asString()).isEqualTo("document-1");
        assertThat(requests).extracting(RecordedRequest::path).containsExactly(
                "/api/v1/datasets/dataset-1/documents",
                "/api/v1/datasets/dataset-1/chunks");
        assertThat(requests.get(1).body()).contains("document-1");
    }

    @Test
    void shouldDecodeChatEventStream() {
        Flux<JsonNode> stream = client.chat("chat-1", "session-1", "什么是 RAG？");

        StepVerifier.create(stream)
                .assertNext(event -> assertThat(event.path("data").path("answer").asString()).isEqualTo("检索"))
                .assertNext(event -> assertThat(event.path("data").path("answer").asString()).isEqualTo("增强生成"))
                .assertNext(event -> assertThat(event.path("data").asBoolean()).isTrue())
                .verifyComplete();
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.path()).isEqualTo("/api/v1/chat/completions");
            assertThat(request.body()).contains("\"legacy\":false");
        });
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new RecordedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION),
                body));

        if (exchange.getRequestURI().getPath().endsWith("/chat/completions")) {
            write(exchange, MediaType.TEXT_EVENT_STREAM_VALUE,
                    "data:{\"code\":0,\"data\":{\"answer\":\"检索\"}}\n\n"
                            + "data:{\"code\":0,\"data\":{\"answer\":\"增强生成\"}}\n\n"
                            + "data:{\"code\":0,\"data\":true}\n\n");
            return;
        }
        if (exchange.getRequestURI().getPath().endsWith("/documents")) {
            write(exchange, MediaType.APPLICATION_JSON_VALUE,
                    "{\"code\":0,\"data\":[{\"id\":\"document-1\"}]}");
            return;
        }
        if (exchange.getRequestURI().getPath().endsWith("/chunks")) {
            write(exchange, MediaType.APPLICATION_JSON_VALUE, "{\"code\":0}");
            return;
        }
        write(exchange, MediaType.APPLICATION_JSON_VALUE,
                "{\"code\":0,\"data\":{\"id\":\"dataset-1\"}}");
    }

    private void write(HttpExchange exchange, String contentType, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record RecordedRequest(String method, String path, String authorization, String body) {
    }
}
