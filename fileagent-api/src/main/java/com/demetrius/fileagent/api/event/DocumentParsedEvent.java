package com.demetrius.fileagent.api.event;

import java.time.LocalDateTime;

/**
 * 文档解析完成事件。
 * 由 document 域发布，chat 域订阅用于建立/更新检索索引。
 */
public record DocumentParsedEvent(
        Long documentId,
        Long sessionId,
        String filename,
        int chunkCount,
        LocalDateTime occurredAt
) implements DomainEvent {
}
