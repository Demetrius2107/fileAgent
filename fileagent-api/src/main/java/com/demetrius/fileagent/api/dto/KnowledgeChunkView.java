package com.demetrius.fileagent.api.dto;

import java.util.Map;

/**
 * 知识片段视图（分块查看接口返回，metadata 含 chunkType/parentId/sourceType 等）
 */
public record KnowledgeChunkView(
        String chunkId,
        int chunkIndex,
        String chunkType,
        String content,
        Map<String, Object> metadata
) {
}
