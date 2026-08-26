package com.demetrius.fileagent.chat.interfaces;

import com.demetrius.fileagent.api.dto.ChatReq;
import com.demetrius.fileagent.api.dto.ChatStreamEvent;
import com.demetrius.fileagent.chat.application.ChatAppService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

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
@RequestMapping("/api/sessions/{sessionId}/chat")
@Tag(name = "对话")
public class ChatController {

    private final ChatAppService chatAppService;

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> chat(
            @PathVariable Long sessionId,
            @RequestBody ChatReq req) {
        return chatAppService.chat(sessionId, req)
                .map(event -> ServerSentEvent.<ChatStreamEvent>builder()
                        .event(event.type())
                        .data(event)
                        .build());
    }
}
