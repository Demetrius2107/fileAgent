package com.demetrius.ragflowquickstart;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

/**
 * RAGFlow HTTP API 客户端，完整委托数据集、文档解析和聊天能力给 RAGFlow。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-09-04
 */
@Component
public class RagFlowClient {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RagFlowProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public RagFlowClient(RagFlowProperties properties, ObjectMapper objectMapper, WebClient.Builder builder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = builder.baseUrl(properties.apiBaseUrl()).build();
    }

    public Mono<JsonNode> createDataset(String name) {
        return jsonRequest(HttpMethod.POST, "/datasets", Map.of("name", name));
    }

    /** 上传文件后立即触发 RAGFlow 内置解析，并返回上传产生的文档。 */
    public Mono<UploadResult> uploadAndParse(String datasetId, Flux<FilePart> files) {
        requireConfigured();
        return files.collectList()
                .flatMap(fileParts -> upload(datasetId, fileParts))
                .flatMap(uploadResponse -> {
                    List<String> documentIds = StreamSupport.stream(
                                    uploadResponse.path("data").spliterator(), false)
                            .map(document -> document.path("id").asString())
                            .filter(id -> !id.isBlank())
                            .toList();
                    if (documentIds.isEmpty()) {
                        return Mono.error(new RagFlowApiException("RAGFlow 上传成功但未返回 document id"));
                    }
                    return jsonRequest(
                            HttpMethod.POST,
                            "/datasets/" + datasetId + "/chunks",
                            Map.of("document_ids", documentIds))
                            .thenReturn(new UploadResult(uploadResponse.path("data"), true));
                });
    }

    public Mono<JsonNode> listDocuments(String datasetId) {
        return jsonRequest(HttpMethod.GET, "/datasets/" + datasetId + "/documents?page=1&page_size=100", null);
    }

    public Mono<JsonNode> createChat(String name, String datasetId) {
        return jsonRequest(HttpMethod.POST, "/chats", Map.of(
                "name", name,
                "dataset_ids", List.of(datasetId)));
    }

    public Mono<JsonNode> createSession(String chatId, String name) {
        return jsonRequest(HttpMethod.POST, "/chats/" + chatId + "/sessions", Map.of("name", name));
    }

    /** 使用 RAGFlow 保存的会话历史发起流式问答，只提交本轮问题。 */
    public Flux<JsonNode> chat(String chatId, String sessionId, String question) {
        requireConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("session_id", sessionId);
        body.put("question", question);
        body.put("stream", true);
        body.put("legacy", false);

        return webClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.isError(), response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(message -> new RagFlowApiException(
                                "RAGFlow HTTP " + response.statusCode().value() + ": " + message)))
                .bodyToFlux(SSE_TYPE)
                .mapNotNull(ServerSentEvent::data)
                .map(this::parseStreamEvent)
                .map(this::requireSuccess);
    }

    private Mono<JsonNode> upload(String datasetId, List<FilePart> files) {
        if (files.isEmpty()) {
            return Mono.error(new ServerWebInputException("至少选择一个文件"));
        }
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        for (FilePart file : files) {
            MultipartBodyBuilder.PartBuilder part = body
                    .asyncPart("file", file.content(), DataBuffer.class)
                    .filename(file.filename());
            MediaType contentType = file.headers().getContentType();
            if (contentType != null) {
                part.contentType(contentType);
            }
        }
        return webClient.post()
                .uri("/datasets/{datasetId}/documents", datasetId)
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build()))
                .retrieve()
                .onStatus(status -> status.isError(), response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(message -> new RagFlowApiException(
                                "RAGFlow HTTP " + response.statusCode().value() + ": " + message)))
                .bodyToMono(JsonNode.class)
                .map(this::requireSuccess);
    }

    private Mono<JsonNode> jsonRequest(HttpMethod method, String path, Object body) {
        requireConfigured();
        WebClient.RequestBodySpec request = webClient.method(method)
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .accept(MediaType.APPLICATION_JSON);
        WebClient.RequestHeadersSpec<?> headers = body == null
                ? request
                : request.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
        return headers.retrieve()
                .onStatus(status -> status.isError(), response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(message -> new RagFlowApiException(
                                "RAGFlow HTTP " + response.statusCode().value() + ": " + message)))
                .bodyToMono(JsonNode.class)
                .map(this::requireSuccess);
    }

    private JsonNode parseStreamEvent(String raw) {
        String json = raw.startsWith("data:") ? raw.substring(5).trim() : raw.trim();
        if ("[DONE]".equals(json)) {
            ObjectNode done = objectMapper.createObjectNode();
            done.put("code", 0);
            done.put("data", true);
            return done;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException e) {
            throw new RagFlowApiException("无法解析 RAGFlow 流式响应: " + json, e);
        }
    }

    private JsonNode requireSuccess(JsonNode response) {
        if (response.path("code").asInt(-1) != 0) {
            throw new RagFlowApiException(response.path("message").asString("RAGFlow 请求失败"));
        }
        return response;
    }

    private void requireConfigured() {
        if (!properties.configured()) {
            throw new RagFlowApiException("缺少环境变量 RAGFLOW_API_KEY");
        }
    }

    private String bearerToken() {
        return "Bearer " + properties.apiKey().trim();
    }

    public record UploadResult(JsonNode documents, boolean parsingStarted) {
    }

    /** RAGFlow HTTP 状态、业务状态或响应格式异常。 */
    public static class RagFlowApiException extends RuntimeException {

        public RagFlowApiException(String message) {
            super(message);
        }

        public RagFlowApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
