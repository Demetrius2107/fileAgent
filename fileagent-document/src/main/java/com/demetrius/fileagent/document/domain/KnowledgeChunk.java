package com.demetrius.fileagent.document.domain;

import java.util.Map;

/**
 * 待写入知识索引的文档片段。
 *
 * @author raosaijie
 */
public record KnowledgeChunk(
        String chunkId,
        Long fileId,
        String ragName,
        String knowledgeTag,
        String filename,
        String content,
        int chunkIndex,
        Map<String, Object> metadata) {

    public KnowledgeChunk {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
