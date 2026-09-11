package com.demetrius.fileagent.chat.interfaces;

import com.demetrius.fileagent.api.dto.ChatReq;
import com.demetrius.fileagent.api.dto.ChatStreamEvent;
import com.demetrius.fileagent.chat.application.ChatAppService;
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
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 对话接口：SSE 流式转发，事件名为 ChatStreamEvent.type。
 * 不在此检索、组 Prompt、保存消息或捕获模型异常。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/sessions/{sessionId}/chat")
@Tag(name = "对话")
public class ChatController {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final ChatAppService chatAppService;
    private final Tracer tracer;
    private final ObjectMapper objectMapper;

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> chat(
            @PathVariable Long sessionId,
            @RequestBody ChatReq req,
            HttpServletResponse response) {
        String traceId = currentTraceId();
        if (StringUtils.hasText(traceId)) {
            response.setHeader(TRACE_ID_HEADER, traceId);
        }
        return chatAppService.chat(sessionId, req)
                .map(event -> {
                    ChatStreamEvent responseEvent = "error".equals(event.type())
                            ? event.withTraceId(traceId)
                            : event;
                    return toSse(responseEvent);
                });
    }

    @ExceptionHandler(BizException.class)
    public void handleBizException(BizException e, HttpServletResponse response) throws IOException {
        log.warn("对话业务异常: {}", e.getMessage());
        String traceId = currentTraceId();
        HttpStatus status = e.getCode() == 404 ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        ChatStreamEvent errorEvent = ChatStreamEvent
                .error(String.valueOf(e.getCode()), e.getMessage())
                .withTraceId(traceId);

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

    private ServerSentEvent<ChatStreamEvent> toSse(ChatStreamEvent event) {
        return ServerSentEvent.<ChatStreamEvent>builder()
                .event(event.type())
                .data(event)
                .build();
    }

    private String currentTraceId() {
        Span currentSpan = tracer.currentSpan();
        return currentSpan == null ? null : currentSpan.context().traceId();
    }
}
