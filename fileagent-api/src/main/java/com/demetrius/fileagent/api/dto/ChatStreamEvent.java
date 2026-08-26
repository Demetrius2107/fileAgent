package com.demetrius.fileagent.api.dto;

import java.util.List;

/**
 * SSE 流式对话事件（单一 DTO，不建立继承体系）。
 * 四种 type：message（模型增量）/ sources（回答来源）/ done（结束）/ error（错误）。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
public record ChatStreamEvent(
        String type,
        String content,
        String answerSource,
        List<String> files,
        Long messageId,
        String code,
        String message
) {
    public ChatStreamEvent {
        files = files == null ? List.of() : List.copyOf(files);
    }

    public static ChatStreamEvent message(String content) {
        return new ChatStreamEvent("message", content, null, List.of(), null, null, null);
    }

    public static ChatStreamEvent sources(String answerSource, List<String> files) {
        return new ChatStreamEvent("sources", null, answerSource, files, null, null, null);
    }

    public static ChatStreamEvent done(Long messageId) {
        return new ChatStreamEvent("done", null, null, List.of(), messageId, null, null);
    }

    public static ChatStreamEvent error(String code, String message) {
        return new ChatStreamEvent("error", null, null, List.of(), null, code, message);
    }
}
