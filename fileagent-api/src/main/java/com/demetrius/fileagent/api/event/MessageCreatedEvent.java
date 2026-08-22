package com.demetrius.fileagent.api.event;

import com.demetrius.fileagent.api.enums.MessageType;

import java.time.LocalDateTime;

/**
 * 消息创建领域事件。
 * 由 fileagent-session 发布，chat 域订阅（用于携带历史上下文 / 触发后续推理）。
 */
public record MessageCreatedEvent(
        Long messageId,
        Long sessionId,
        MessageType role,
        String content,
        LocalDateTime occurredAt
) implements DomainEvent {
}
