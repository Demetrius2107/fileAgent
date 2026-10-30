package com.demetrius.fileagent.sandbox.interfaces;

import com.demetrius.fileagent.api.dto.sandbox.SandboxExecutionRequest;
import com.demetrius.fileagent.api.dto.sandbox.SandboxExecutionResponse;
import com.demetrius.fileagent.common.result.ApiResult;
import com.demetrius.fileagent.sandbox.application.SandboxExecutionService;
import com.demetrius.fileagent.sandbox.infrastructure.security.SandboxWorkerAuthentication;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Sandbox Worker 内部执行入口：固定请求契约和 Bearer Token 后才会交给受限容器运行时。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
@RestController
@RequestMapping("/internal/sandbox/executions")
@RequiredArgsConstructor
public class SandboxExecutionController {

    static final String AUTHORIZATION_HEADER = "Authorization";
    static final int MAX_CODE_BYTES = 20 * 1024;
    static final int MAX_INPUT_BYTES = 64 * 1024;

    private static final Set<String> REQUEST_FIELDS = Set.of("executionId", "code", "inputJson");

    private final SandboxExecutionService sandboxExecutionService;
    private final SandboxWorkerAuthentication authentication;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<ApiResult<SandboxExecutionResponse>> execute(
            @RequestHeader(value = AUTHORIZATION_HEADER, required = false) String authorization,
            @RequestBody(required = false) JsonNode rawRequest) {
        if (!authentication.matches(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.fail(401, "Sandbox Worker 认证失败"));
        }
        return ResponseEntity.ok(ApiResult.ok(sandboxExecutionService.execute(decode(rawRequest))));
    }

    private SandboxExecutionRequest decode(JsonNode rawRequest) {
        if (rawRequest == null || !rawRequest.isObject()
                || !rawRequest.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet())
                .equals(REQUEST_FIELDS)
                || !REQUEST_FIELDS.stream().allMatch(field -> rawRequest.get(field).isString())) {
            throw new IllegalArgumentException("Sandbox 请求只能包含 executionId、code、inputJson 文本字段");
        }
        SandboxExecutionRequest request;
        try {
            request = objectMapper.treeToValue(rawRequest, SandboxExecutionRequest.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Sandbox 请求格式不合法", e);
        }
        validateRequest(request);
        return request;
    }

    private void validateRequest(SandboxExecutionRequest request) {
        try {
            UUID.fromString(request.executionId());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Sandbox executionId 必须是 UUID", e);
        }
        if (request.code().isBlank()) {
            throw new IllegalArgumentException("Sandbox code 不能为空");
        }
        if (request.inputJson().isBlank()) {
            throw new IllegalArgumentException("Sandbox inputJson 不能为空");
        }
        if (request.code().getBytes(StandardCharsets.UTF_8).length > MAX_CODE_BYTES) {
            throw new IllegalArgumentException("Sandbox code 超过 20 KB");
        }
        if (request.inputJson().getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("Sandbox inputJson 超过 64 KB");
        }
        try {
            JsonNode input = objectMapper.readTree(request.inputJson());
            if (input == null || !input.isObject()) {
                throw new IllegalArgumentException("Sandbox inputJson 必须是 JSON object");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Sandbox inputJson 不是合法 JSON object", e);
        }
    }

}
