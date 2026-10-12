package com.demetrius.fileagent.agent.interfaces;

import com.demetrius.fileagent.agent.application.AgentApprovalWorkflowService;
import com.demetrius.fileagent.agent.infrastructure.config.AgentProperties;
import com.demetrius.fileagent.api.dto.AgentApprovalDecision;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import com.demetrius.fileagent.api.port.AgentRuntimePort;
import com.demetrius.fileagent.common.exception.BizException;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Agent 操作审批 SSE 接口：只接收一次允许或拒绝，不允许客户端重传动作参数或永久授权规则。
 *
 * @author raosaijie
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Agent")
public class AgentApprovalController {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String CLIENT_HEADER = "X-FileAgent-Client";
    private static final String WEB_CLIENT = "web";

    private final AgentApprovalWorkflowService workflowService;
    private final AgentRuntimePort agentRuntimePort;
    private final AgentProperties properties;
    private final Tracer tracer;
    private final ObjectMapper objectMapper;

    @PostMapping(value = "/api/agent-runs/{runId}/approvals/{approvalId}",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentRunEvent>> decide(
            @PathVariable String runId,
            @PathVariable String approvalId,
            @RequestBody AgentApprovalDecision request,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        ensureEnabled();
        ensureSameOriginWebClient(servletRequest);
        ensureApprovalExecutionEnabled(request);
        String traceId = currentTraceId();
        if (StringUtils.hasText(traceId)) {
            servletResponse.setHeader(TRACE_ID_HEADER, traceId);
        }
        workflowService.decide(runId, approvalId, request);
        servletResponse.setHeader("X-Agent-Run-Id", runId);
        return agentRuntimePort.events(runId, lastEventId)
                .map(event -> ServerSentEvent.<AgentRunEvent>builder()
                        .id(event.eventId())
                        .event(event.type())
                        .data(event)
                        .build());
    }

    @ExceptionHandler(BizException.class)
    public void handleBizException(BizException exception, HttpServletResponse response) throws IOException {
        log.warn("Agent 审批业务异常: {}", exception.getMessage());
        String traceId = currentTraceId();
        HttpStatus resolved = HttpStatus.resolve(exception.getCode());
        HttpStatus status = resolved != null && resolved.is4xxClientError() ? resolved : HttpStatus.BAD_REQUEST;
        AgentRunEvent errorEvent = AgentRunEvent.error(String.valueOf(exception.getCode()), exception.getMessage(), traceId);
        response.setStatus(status.value());
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (StringUtils.hasText(traceId)) {
            response.setHeader(TRACE_ID_HEADER, traceId);
        }
        response.getWriter().write("event:" + errorEvent.type() + "\n");
        response.getWriter().write("data:" + objectMapper.writeValueAsString(errorEvent) + "\n\n");
        response.flushBuffer();
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new BizException(403, "AGENT_FEATURE_DISABLED: Agent 模式未启用");
        }
    }

    /** 动作总开关关闭后仍可拒绝或取消既有审批，但不得再批准并恢复副作用工具。 */
    private void ensureApprovalExecutionEnabled(AgentApprovalDecision request) {
        if (request != null && request.decision() == AgentApprovalDecision.Decision.APPROVE
                && !properties.isActionsEnabled()) {
            throw new BizException(403, "AGENT_ACTIONS_DISABLED: Agent 动作工具未启用");
        }
    }

    /**
     * 本地单用户界面仍要求浏览器 Origin 与不可由脚本伪造的 Host 一致，避免跨站直接提交审批决定。
     */
    private void ensureSameOriginWebClient(HttpServletRequest request) {
        if (!WEB_CLIENT.equals(request.getHeader(CLIENT_HEADER))) {
            throw new BizException(403, "AGENT_APPROVAL_CLIENT_FORBIDDEN");
        }
        String originHeader = request.getHeader("Origin");
        String hostHeader = request.getHeader("Host");
        if (!StringUtils.hasText(originHeader) || !StringUtils.hasText(hostHeader)) {
            throw new BizException(403, "AGENT_APPROVAL_ORIGIN_REQUIRED");
        }
        try {
            URI origin = URI.create(originHeader);
            if (!isHttpOrigin(origin)) {
                throw new BizException(403, "AGENT_APPROVAL_ORIGIN_FORBIDDEN");
            }
            HostPort host = parseHost(hostHeader);
            int originPort = effectivePort(origin.getScheme(), origin.getPort());
            if (!origin.getHost().equalsIgnoreCase(host.host())
                    || (host.port() >= 0 && originPort != host.port())
                    || (host.port() < 0 && originPort != 80 && originPort != 443)) {
                throw new BizException(403, "AGENT_APPROVAL_ORIGIN_FORBIDDEN");
            }
        } catch (IllegalArgumentException exception) {
            throw new BizException(403, "AGENT_APPROVAL_ORIGIN_FORBIDDEN");
        }
    }

    private boolean isHttpOrigin(URI origin) {
        if (origin.getHost() == null || origin.getUserInfo() != null || origin.getQuery() != null
                || origin.getFragment() != null) {
            return false;
        }
        String scheme = origin.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private HostPort parseHost(String hostHeader) {
        URI host = URI.create("http://" + hostHeader.trim());
        if (host.getHost() == null || host.getUserInfo() != null || !host.getPath().isEmpty()
                || host.getQuery() != null || host.getFragment() != null) {
            throw new IllegalArgumentException("Host 不合法");
        }
        return new HostPort(host.getHost().toLowerCase(Locale.ROOT), host.getPort());
    }

    private int effectivePort(String scheme, int port) {
        if (port >= 0) {
            return port;
        }
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private String currentTraceId() {
        Span currentSpan = tracer.currentSpan();
        return currentSpan == null ? null : currentSpan.context().traceId();
    }

    private record HostPort(String host, int port) {
    }
}
