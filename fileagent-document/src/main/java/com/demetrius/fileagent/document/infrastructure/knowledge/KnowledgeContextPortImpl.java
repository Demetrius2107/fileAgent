package com.demetrius.fileagent.document.infrastructure.knowledge;

import com.demetrius.fileagent.api.port.KnowledgeContextPort;
import com.demetrius.fileagent.document.domain.KnowledgeChunk;
import com.demetrius.fileagent.document.domain.KnowledgeIndexRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@link KnowledgeContextPort} 实现：将领域 {@link KnowledgeChunk} 映射为 API DTO。
 * <p>
 * 不在 DTO 中透出 ES 索引名、向量或原始 metadata map，只暴露正文与可引用来源字段。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeContextPortImpl implements KnowledgeContextPort {

    private final KnowledgeIndexRepository knowledgeIndexRepository;

    @Override
    public List<KnowledgeChunkContext> read(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }
        return knowledgeIndexRepository.findByChunkIds(chunkIds).stream()
                .map(this::toContext)
                .toList();
    }

    private KnowledgeChunkContext toContext(KnowledgeChunk chunk) {
        Map<String, Object> metadata = chunk.metadata() == null ? Map.of() : chunk.metadata();
        return new KnowledgeChunkContext(
                chunk.chunkId(),
                chunk.fileId(),
                chunk.filename(),
                chunk.content(),
                stringValue(metadata.get("sheetName")),
                stringValue(metadata.get("sectionId")),
                chunk.chunkIndex());
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
