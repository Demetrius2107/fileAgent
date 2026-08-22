package com.demetrius.fileagent.api.dto;

import com.demetrius.fileagent.api.enums.ParseStatus;

/**
 * 文档概要（跨域传递，不暴露实体）
 */
public record DocumentSummary(
        Long id,
        String filename,
        ParseStatus parseStatus,
        String createdAt
) {
}
