package com.demetrius.fileagent.action.infrastructure.http;

import com.demetrius.fileagent.action.infrastructure.http.RegisteredHttpActionRegistry.RegisteredEndpoint;
import com.demetrius.fileagent.api.enums.ActionCancelStatus;
import com.demetrius.fileagent.api.enums.ActionExecutionStatus;
import com.demetrius.fileagent.api.enums.ActionRiskLevel;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 预注册 HTTP 动作执行器：禁用代理、重定向和自动重试，并对响应实施流式大小限制。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-20
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "fileagent.action.http", name = "enabled", havingValue = "true")
public class RegisteredHttpExecutor {

    private static final int MAX_RESPONSE_HEADERS = 100;
    private static final int MAX_RESPONSE_HEADER_BYTES = 16 * 1024;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final RegisteredHttpActionRegistry registry;
    private final ObjectMapper objectMapper;
    private final SafeHttpUriBuilder uriBuilder;
    private final ValidatedDns validatedDns;
    private final String mockToken;
    private final Map<String, ActiveRequest> activeRequests = new ConcurrentHashMap<>();

    public RegisteredHttpExecutor(RegisteredHttpActionRegistry registry,
                                  ObjectMapper objectMapper,
                                  SafeHttpUriBuilder uriBuilder,
                                  BlockedAddressPolicy blockedAddressPolicy,
                                  @Value("${fileagent.action.mock.token:}") String mockToken) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.uriBuilder = uriBuilder;
        this.validatedDns = new ValidatedDns(blockedAddressPolicy);
        this.mockToken = mockToken;
    }

    /** 执行一次已注册动作；本方法不做网络重试，无法确认远端结果时返回 OUTCOME_UNKNOWN。 */
    public HttpExecutionResult execute(String executionId, String endpointId,
                                       String operationId, Map<String, Object> arguments) {
        long startedNanos = System.nanoTime();
        RegisteredEndpoint endpoint = registry.requireEndpoint(endpointId);
        ActiveRequest active = new ActiveRequest();
        try {
            SafeHttpUriBuilder.BuiltHttpRequest built = uriBuilder.build(
                    endpoint.baseUrl(), endpoint.pathTemplate(), arguments, endpoint.requestSchema());
            Request request = buildRequest(endpoint, built, operationId);
            OkHttpClient client = client(endpoint, active);
            Call call = client.newCall(request);
            active.call = call;
            if (activeRequests.putIfAbsent(executionId, active) != null) {
                return result(ActionExecutionStatus.OUTCOME_UNKNOWN, "HTTP_EXECUTION_ALREADY_ACTIVE",
                        null, null, false, 0);
            }
            if (active.cancelled.get()) {
                call.cancel();
            }
            try (Response response = call.execute()) {
                HttpExecutionResult result = readResponse(endpoint, response);
                logResult(endpointId, executionId, result, startedNanos);
                return result;
            } catch (IOException e) {
                HttpExecutionResult result = transportFailure(endpoint, active, e);
                logResult(endpointId, executionId, result, startedNanos);
                return result;
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            HttpExecutionResult result = result(ActionExecutionStatus.FAILED,
                    "HTTP_REQUEST_REJECTED", null, null, false, 0);
            logResult(endpointId, executionId, result, startedNanos);
            return result;
        } finally {
            activeRequests.remove(executionId, active);
        }
    }

    /** 取消当前进程内的活动请求；请求开始发送后只能保守返回 OUTCOME_UNKNOWN。 */
    public ActionCancelStatus cancel(String executionId) {
        ActiveRequest active = activeRequests.get(executionId);
        if (active == null || active.call == null) {
            return ActionCancelStatus.NOT_STARTED;
        }
        active.cancelled.set(true);
        active.call.cancel();
        return active.requestStarted.get() ? ActionCancelStatus.OUTCOME_UNKNOWN : ActionCancelStatus.CANCELLED;
    }

    private Request buildRequest(RegisteredEndpoint endpoint,
                                 SafeHttpUriBuilder.BuiltHttpRequest built,
                                 String operationId) {
        Request.Builder request = new Request.Builder().url(built.url());
        if (endpoint.idempotencyHeader() != null) {
            request.header(endpoint.idempotencyHeader(), requireHeaderValue(operationId, "operationId"));
        }
        if (endpoint.credentialRef() != null) {
            String credential = endpoint.mockEndpoint() ? mockToken : System.getenv(endpoint.credentialRef());
            if (credential == null || credential.isBlank()) {
                throw new IllegalStateException("HTTP 凭证未配置");
            }
            request.header("Authorization", "Bearer " + requireHeaderValue(credential, "credential"));
        }
        RequestBody body = null;
        if (!built.bodyArguments().isEmpty() || requiresBody(endpoint.method())) {
            try {
                body = RequestBody.create(objectMapper.writeValueAsBytes(built.bodyArguments()), JSON);
            } catch (Exception e) {
                throw new IllegalArgumentException("HTTP 请求体无法序列化", e);
            }
        }
        if ("GET".equals(endpoint.method()) && body != null) {
            throw new IllegalArgumentException("GET Endpoint 不允许 body 参数");
        }
        return request.method(endpoint.method(), body).build();
    }

    private OkHttpClient client(RegisteredEndpoint endpoint, ActiveRequest active) {
        return new OkHttpClient.Builder()
                .dns(endpoint.mockLoopback() ? hostname -> {
                    if (!"127.0.0.1".equals(hostname)) {
                        throw new UnknownHostException("HTTP_SSRF_BLOCKED");
                    }
                    return List.of(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
                } : validatedDns)
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectTimeout(endpoint.connectTimeout())
                .readTimeout(endpoint.readTimeout())
                .writeTimeout(endpoint.readTimeout())
                .callTimeout(endpoint.callTimeout())
                .eventListener(new EventListener() {
                    @Override
                    public void requestHeadersStart(Call call) {
                        active.requestStarted.set(true);
                    }
                })
                .build();
    }

    private HttpExecutionResult readResponse(RegisteredEndpoint endpoint, Response response) throws IOException {
        if (response.isRedirect()) {
            return uncertainForWrite(endpoint, "HTTP_REDIRECT_REJECTED", 0);
        }
        if (response.headers().size() > MAX_RESPONSE_HEADERS
                || responseHeaderBytes(response) > MAX_RESPONSE_HEADER_BYTES) {
            return uncertainForWrite(endpoint, "HTTP_RESPONSE_HEADERS_TOO_LARGE", 0);
        }
        String externalRequestId = firstNonBlank(
                response.header("X-Request-Id"), response.header("X-Correlation-Id"));
        ResponseBody responseBody = response.body();
        String contentType = normalizedContentType(response.header("Content-Type"));
        if (responseBody != null && responseBody.contentLength() != 0
                && !endpoint.allowedContentTypes().contains(contentType)) {
            return uncertainForWrite(endpoint, "HTTP_CONTENT_TYPE_REJECTED", 0);
        }
        BoundedBody body;
        try {
            body = readBounded(responseBody, endpoint.maxResponseBytes());
        } catch (ResponseTooLargeException e) {
            return uncertainForWrite(endpoint, "HTTP_RESPONSE_TOO_LARGE", e.bytesRead);
        }
        if (response.isSuccessful()) {
            return result(ActionExecutionStatus.SUCCEEDED, "HTTP_" + response.code(), body.text,
                    externalRequestId, false, body.bytes);
        }
        return result(ActionExecutionStatus.FAILED, "HTTP_" + response.code(), body.text,
                externalRequestId, response.code() >= 500, body.bytes);
    }

    private HttpExecutionResult transportFailure(RegisteredEndpoint endpoint,
                                                 ActiveRequest active,
                                                 IOException error) {
        if (active.cancelled.get()) {
            return active.requestStarted.get()
                    ? result(ActionExecutionStatus.OUTCOME_UNKNOWN, "HTTP_OUTCOME_UNKNOWN",
                    null, null, false, 0)
                    : result(ActionExecutionStatus.CANCELLED, "HTTP_CANCELLED",
                    null, null, false, 0);
        }
        if (containsCode(error, "HTTP_SSRF_BLOCKED")) {
            return result(ActionExecutionStatus.FAILED, "HTTP_SSRF_BLOCKED",
                    null, null, false, 0);
        }
        if (active.requestStarted.get() && endpoint.riskLevel() != ActionRiskLevel.READ_ONLY) {
            return result(ActionExecutionStatus.OUTCOME_UNKNOWN, "HTTP_OUTCOME_UNKNOWN",
                    null, null, false, 0);
        }
        return result(ActionExecutionStatus.FAILED, "HTTP_TRANSPORT_FAILED",
                null, null, true, 0);
    }

    private static HttpExecutionResult uncertainForWrite(RegisteredEndpoint endpoint,
                                                         String code, long bytes) {
        ActionExecutionStatus status = endpoint.riskLevel() == ActionRiskLevel.READ_ONLY
                ? ActionExecutionStatus.FAILED : ActionExecutionStatus.OUTCOME_UNKNOWN;
        return result(status, code, null, null, status == ActionExecutionStatus.FAILED, bytes);
    }

    private static BoundedBody readBounded(ResponseBody responseBody, int maxBytes) throws IOException {
        if (responseBody == null) {
            return new BoundedBody("", 0);
        }
        try (InputStream input = responseBody.byteStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8 * 1024))) {
            byte[] buffer = new byte[8 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new ResponseTooLargeException(total);
                }
                output.write(buffer, 0, read);
            }
            return new BoundedBody(output.toString(StandardCharsets.UTF_8), total);
        }
    }

    private static int responseHeaderBytes(Response response) {
        int bytes = 0;
        for (int index = 0; index < response.headers().size(); index++) {
            bytes += response.headers().name(index).getBytes(StandardCharsets.UTF_8).length;
            bytes += response.headers().value(index).getBytes(StandardCharsets.UTF_8).length;
            bytes += 4;
        }
        return bytes;
    }

    private static String normalizedContentType(String value) {
        if (value == null) {
            return "";
        }
        int separator = value.indexOf(';');
        return (separator < 0 ? value : value.substring(0, separator)).strip().toLowerCase(Locale.ROOT);
    }

    private static boolean requiresBody(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
    }

    private static String requireHeaderValue(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 4_096
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " 不是安全 Header 值");
        }
        return value;
    }

    private static boolean containsCode(Throwable error, String code) {
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(code)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.length() > 200 ? first.substring(0, 200) : first;
        }
        if (second != null && !second.isBlank()) {
            return second.length() > 200 ? second.substring(0, 200) : second;
        }
        return null;
    }

    private static HttpExecutionResult result(ActionExecutionStatus status, String resultCode,
                                              String body, String externalRequestId,
                                              boolean recoverable, long bytesRead) {
        return new HttpExecutionResult(status, resultCode, body, externalRequestId, recoverable, bytesRead);
    }

    private static void logResult(String endpointId, String executionId,
                                  HttpExecutionResult result, long startedNanos) {
        long durationMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        log.info("注册 HTTP 动作执行结束: endpointId={}, executionId={}, status={}, resultCode={}, "
                        + "durationMs={}, responseBytes={}",
                endpointId, executionId, result.status(), result.resultCode(), durationMillis, result.bytesRead());
    }

    /** 不含凭证和请求正文的单次 HTTP 执行结果。 */
    public record HttpExecutionResult(
            ActionExecutionStatus status,
            String resultCode,
            String body,
            String externalRequestId,
            boolean recoverable,
            long bytesRead) {
    }

    private static final class ActiveRequest {
        private final AtomicBoolean requestStarted = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Call call;
    }

    private record BoundedBody(String text, int bytes) {
    }

    private static final class ResponseTooLargeException extends IOException {
        private final int bytesRead;

        private ResponseTooLargeException(int bytesRead) {
            this.bytesRead = bytesRead;
        }
    }
}
