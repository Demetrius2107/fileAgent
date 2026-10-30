package com.demetrius.fileagent.action.infrastructure.sandbox;

import com.demetrius.fileagent.action.infrastructure.config.SandboxClientProperties;
import com.demetrius.fileagent.api.dto.sandbox.SandboxExecutionResponse;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * 主应用到本地 Sandbox Worker 的受限客户端，不接受任意 URL、代理、重定向或调用方运行参数。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
@Component
@ConditionalOnProperty(prefix = "fileagent.sandbox", name = "enabled", havingValue = "true")
public class SandboxClient {

    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Set<String> WORKER_STATUSES = Set.of("SUCCEEDED", "FAILED");

    private final SandboxClientProperties properties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    public SandboxClient(SandboxClientProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .callTimeout(properties.getCallTimeout())
                .build();
    }

    /**
     * 向 Worker 发送唯一固定的三字段请求；任何传输或协议异常都转换为可审计稳定错误码。
     */
    public SandboxExecutionResponse execute(String executionId, String code, String inputJson) {
        try {
            RequestBody body = RequestBody.create(objectMapper.writeValueAsBytes(Map.of(
                    "executionId", executionId, "code", code, "inputJson", inputJson)), JSON);
            Request request = new Request.Builder()
                    .url(properties.workerUri().resolve("/internal/sandbox/executions").toString())
                    .header("Authorization", "Bearer " + properties.getToken())
                    .post(body)
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isRedirect()) {
                    return failed("SANDBOX_WORKER_REDIRECT_REJECTED");
                }
                if (!response.isSuccessful()) {
                    return failed("SANDBOX_WORKER_HTTP_" + response.code());
                }
                return parseWorkerResponse(readBounded(response.body()));
            }
        } catch (ResponseTooLargeException e) {
            return failed("SANDBOX_WORKER_RESPONSE_TOO_LARGE");
        } catch (IOException e) {
            return failed("SANDBOX_WORKER_UNAVAILABLE");
        } catch (Exception e) {
            return failed("SANDBOX_WORKER_PROTOCOL_ERROR");
        }
    }

    private SandboxExecutionResponse parseWorkerResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || root.get("code") == null || root.get("code").asInt() != 0
                    || root.get("data") == null || !root.get("data").isObject()) {
                return failed("SANDBOX_WORKER_PROTOCOL_ERROR");
            }
            SandboxExecutionResponse response = objectMapper.treeToValue(root.get("data"), SandboxExecutionResponse.class);
            if (response == null || !WORKER_STATUSES.contains(response.status())
                    || response.stdout() == null || response.stderr() == null) {
                return failed("SANDBOX_WORKER_PROTOCOL_ERROR");
            }
            return response;
        } catch (Exception e) {
            return failed("SANDBOX_WORKER_PROTOCOL_ERROR");
        }
    }

    private static String readBounded(ResponseBody body) throws IOException, ResponseTooLargeException {
        if (body == null) {
            throw new ResponseTooLargeException();
        }
        try (InputStream input = body.byteStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4_096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new ResponseTooLargeException();
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static SandboxExecutionResponse failed(String errorCode) {
        return new SandboxExecutionResponse("FAILED", "", "", errorCode);
    }

    private static final class ResponseTooLargeException extends Exception {
    }
}
