package com.demetrius.fileagent.model.dto;

/**
 * 会话信息（列表 / 创建返回）
 */
public record SessionDto(
        Long id,
        String title,
        String createdAt
) {
}
