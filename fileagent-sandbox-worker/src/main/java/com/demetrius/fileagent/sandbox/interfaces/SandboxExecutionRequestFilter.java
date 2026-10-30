package com.demetrius.fileagent.sandbox.interfaces;

import com.demetrius.fileagent.common.result.ApiResult;
import com.demetrius.fileagent.sandbox.infrastructure.security.SandboxWorkerAuthentication;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Sandbox 执行入口的认证与原始请求体上限：在 Jackson 解析前拒绝未认证或超限请求。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class SandboxExecutionRequestFilter extends OncePerRequestFilter {

    static final int MAX_REQUEST_BYTES = 192 * 1024;

    private static final String EXECUTION_PATH = "/internal/sandbox/executions";

    private final SandboxWorkerAuthentication authentication;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !"POST".equalsIgnoreCase(request.getMethod()) || !EXECUTION_PATH.equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!authentication.matches(request.getHeader(SandboxExecutionController.AUTHORIZATION_HEADER))) {
            writeFailure(response, HttpServletResponse.SC_UNAUTHORIZED, 401, "Sandbox Worker 认证失败");
            return;
        }
        if (request.getContentLengthLong() > MAX_REQUEST_BYTES) {
            writeFailure(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, 413, "Sandbox 请求体超过限制");
            return;
        }
        byte[] body;
        try {
            body = readBounded(request.getInputStream());
        } catch (RequestBodyTooLargeException ignored) {
            writeFailure(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, 413, "Sandbox 请求体超过限制");
            return;
        }
        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private byte[] readBounded(ServletInputStream input) throws IOException, RequestBodyTooLargeException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4_096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_REQUEST_BYTES) {
                    throw new RequestBodyTooLargeException();
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private void writeFailure(HttpServletResponse response, int status, int code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8);
        objectMapper.writeValue(response.getOutputStream(), ApiResult.fail(code, message));
    }

    private static final class RequestBodyTooLargeException extends Exception {
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ByteArrayServletInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    private static final class ByteArrayServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream input;

        private ByteArrayServletInputStream(byte[] body) {
            this.input = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return input.read();
        }

        @Override
        public boolean isFinished() {
            return input.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("Sandbox Worker 不支持异步请求体读取");
        }
    }
}
