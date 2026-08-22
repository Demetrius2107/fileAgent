package com.demetrius.fileagent.api.dto;

import com.demetrius.fileagent.api.enums.MessageType;

/**
 * 消息概要（跨域传递，不暴露实体）
 */
public record MessageDto(
        Long id,
        Long sessionId,
        MessageType role,
        String content,
        String actionJson,
        String createdAt
) {
}
