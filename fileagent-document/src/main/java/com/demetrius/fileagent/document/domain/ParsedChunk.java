package com.demetrius.fileagent.document.domain;

import java.util.Map;

/**
 * 文档解析后的知识块，包含正文及通用位置元数据。
 *
 * @author raosaijie
 */
public record ParsedChunk(String content, Map<String, Object> metadata) {

    public ParsedChunk {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ParsedChunk text(String content) {
        return new ParsedChunk(content, Map.of(
                "sourceType", "text",
                "sectionId", "document"));
    }
}
