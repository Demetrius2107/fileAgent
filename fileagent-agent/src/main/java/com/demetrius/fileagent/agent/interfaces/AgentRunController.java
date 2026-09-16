package com.demetrius.fileagent.agent.interfaces;

import com.demetrius.fileagent.agent.application.AgentRunAppService;
import com.demetrius.fileagent.agent.interfaces.dto.StartAgentRunRequest;
import com.demetrius.fileagent.api.dto.AgentRunEvent;
import com.demetrius.fileagent.common.exception.BizException;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Agent 模式 SSE 运行接口。只转发受控事件；敏感配置（apiKey/baseUrl）绝不进入响应。
 * <p>
 * 查询/取消接口见 {@link AgentRunQueryController}，其错误走全局 JSON 处理器，
 * 与 SSE 的 {@code run.failed} 事件契约分离。
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Agent")
public class AgentRunController {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final AgentRunAppService agentRunAppService;
    private final Tracer tracer;
    private final ObjectMapper objectMapper;

    @PostMapping(value = "/api/sessions/{sessionId}/agent-runs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentRunEvent>> start(
            @PathVariable Long sessionId,
            @RequestBody StartAgentRunRequest request,
            HttpServletResponse response) {
        String traceId = currentTraceId();
        if (StringUtils.hasText(traceId)) {
            response.setHeader(TRACE_ID_HEADER, traceId);
        }
        return agentRunAppService.run(sessionId, request, traceId)
                .map(event -> ServerSentEvent.<AgentRunEvent>builder()
                        .event(event.type())
                        .data(event)
                        .build());
    }

    @ExceptionHandler(BizException.class)
    public void handleBizException(BizException e, HttpServletResponse response) throws IOException {
        log.warn("Agent 业务异常: {}", e.getMessage());
        String traceId = currentTraceId();
        HttpStatus status = e.getCode() == 404 ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        AgentRunEvent errorEvent = AgentRunEvent.failed(null, String.valueOf(e.getCode()), e.getMessage(), traceId);

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

    private String currentTraceId() {
        Span currentSpan = tracer.currentSpan();
        return currentSpan == null ? null : currentSpan.context().traceId();
    }
}
