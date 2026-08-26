package com.demetrius.fileagent.api.dto;

import com.demetrius.fileagent.api.enums.ParseStatus;

/**
 * 知识库文件概要（列表展示，不暴露实体）
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-26
 */
public record RagFileSummary(
        Long id,
        String ragName,
        String knowledgeTag,
        String filename,
        ParseStatus status,
        Integer chunkCount,
        String createdAt
) {
}
