package com.demetrius.ragflowquickstart;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * Quickstart 页面使用的同源接口，负责校验输入并转发到 RAGFlow。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-09-04
 */
@RestController
@RequestMapping("/api/ragflow")
public class RagFlowController {

    private final RagFlowProperties properties;
    private final RagFlowClient ragFlowClient;

    public RagFlowController(RagFlowProperties properties, RagFlowClient ragFlowClient) {
        this.properties = properties;
        this.ragFlowClient = ragFlowClient;
    }

    @GetMapping("/status")
    public StatusResponse status() {
        return new StatusResponse(properties.apiBaseUrl(), properties.configured());
    }

    @PostMapping("/datasets")
    public Mono<JsonNode> createDataset(@Valid @RequestBody NameRequest request) {
        return ragFlowClient.createDataset(request.name());
    }

    @PostMapping(value = "/datasets/{datasetId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<RagFlowClient.UploadResult> uploadDocuments(
            @PathVariable String datasetId,
            @RequestPart("files") Flux<FilePart> files) {
        return ragFlowClient.uploadAndParse(datasetId, files);
    }

    @GetMapping("/datasets/{datasetId}/documents")
    public Mono<JsonNode> listDocuments(@PathVariable String datasetId) {
        return ragFlowClient.listDocuments(datasetId);
    }

    @PostMapping("/chats")
    public Mono<JsonNode> createChat(@Valid @RequestBody CreateChatRequest request) {
        return ragFlowClient.createChat(request.name(), request.datasetId());
    }

    @PostMapping("/chats/{chatId}/sessions")
    public Mono<JsonNode> createSession(
            @PathVariable String chatId,
            @Valid @RequestBody NameRequest request) {
        return ragFlowClient.createSession(chatId, request.name());
    }

    @PostMapping(value = "/chat/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<JsonNode>> chat(@Valid @RequestBody ChatRequest request) {
        return ragFlowClient.chat(request.chatId(), request.sessionId(), request.question())
                .map(event -> ServerSentEvent.builder(event).build());
    }

    @ExceptionHandler({RagFlowClient.RagFlowApiException.class, ServerWebInputException.class})
    public ResponseEntity<ProblemDetail> handleClientError(RuntimeException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, exception.getMessage());
        problem.setTitle("RAGFlow 请求失败");
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(problem);
    }

    public record StatusResponse(String apiBaseUrl, boolean configured) {
    }

    public record NameRequest(@NotBlank String name) {
    }

    public record CreateChatRequest(@NotBlank String name, @NotBlank String datasetId) {
    }

    public record ChatRequest(
            @NotBlank String chatId,
            @NotBlank String sessionId,
            @NotBlank String question) {
    }
}
