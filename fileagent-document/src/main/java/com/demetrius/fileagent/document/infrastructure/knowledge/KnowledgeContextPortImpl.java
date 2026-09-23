package com.demetrius.fileagent.document.infrastructure.knowledge;

import com.demetrius.fileagent.api.port.KnowledgeContextPort;
import com.demetrius.fileagent.document.domain.KnowledgeChunk;
import com.demetrius.fileagent.document.domain.KnowledgeIndexRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Comparator;

/**
 * {@link KnowledgeContextPort} 实现：将领域 {@link KnowledgeChunk} 映射为 API DTO。
 * <p>
 * 不在 DTO 中透出 ES 索引名、向量或原始 metadata map，只暴露正文与可引用来源字段。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeContextPortImpl implements KnowledgeContextPort {

    private static final int MAX_OUTLINE_ITEMS = 50;
    private static final int OUTLINE_PREVIEW_CODE_POINTS = 160;

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

    @Override
    public DocumentOutlinePage outline(Long fileId, int afterChunkIndex, int limit) {
        if (fileId == null || limit <= 0) {
            return new DocumentOutlinePage(List.of(), null);
        }
        int pageSize = Math.min(limit, MAX_OUTLINE_ITEMS);
        List<KnowledgeChunk> candidates = knowledgeIndexRepository.findByFileId(fileId).stream()
                .filter(this::isChildChunk)
                .filter(chunk -> chunk.chunkIndex() > afterChunkIndex)
                .sorted(Comparator.comparingInt(KnowledgeChunk::chunkIndex)
                        .thenComparing(KnowledgeChunk::chunkId,
                                Comparator.nullsFirst(String::compareTo)))
                .toList();
        boolean hasNext = candidates.size() > pageSize;
        List<DocumentOutlineItem> items = candidates.stream()
                .limit(pageSize)
                .map(this::toOutlineItem)
                .toList();
        Integer nextChunkIndex = hasNext && !items.isEmpty()
                ? items.getLast().chunkIndex()
                : null;
        return new DocumentOutlinePage(items, nextChunkIndex);
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

    private DocumentOutlineItem toOutlineItem(KnowledgeChunk chunk) {
        Map<String, Object> metadata = chunk.metadata() == null ? Map.of() : chunk.metadata();
        return new DocumentOutlineItem(
                chunk.chunkId(),
                stringValue(metadata.get("parentId")),
                chunk.filename(),
                stringValue(metadata.get("sourceType")),
                stringValue(metadata.get("sheetName")),
                stringValue(metadata.get("sectionId")),
                integerValue(metadata.get("rowIndex")),
                chunk.chunkIndex(),
                preview(chunk.content()));
    }

    private boolean isChildChunk(KnowledgeChunk chunk) {
        return "CHILD".equalsIgnoreCase(stringValue(chunk.metadata().get("chunkType")));
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String preview(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        int end = content.offsetByCodePoints(0,
                Math.min(content.codePointCount(0, content.length()), OUTLINE_PREVIEW_CODE_POINTS));
        return content.substring(0, end);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
