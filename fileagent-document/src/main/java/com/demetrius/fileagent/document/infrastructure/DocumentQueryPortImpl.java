package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.api.dto.DocumentSummary;
import com.demetrius.fileagent.api.port.DocumentQueryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档域对外端口的 JPA 实现（骨架声明，RAG 检索 + 映射逻辑由协作者完成，M1）。
 * 供 chat 域检索文档片段使用。
 */
@Component
@RequiredArgsConstructor
public class DocumentQueryPortImpl implements DocumentQueryPort {

    private final DocumentJpaRepository documentJpaRepository;

    @Override
    public List<DocumentSummary> listParsed(Long sessionId) {
        throw new UnsupportedOperationException("M1: 由协作者实现");
    }

    @Override
    public List<ChunkHit> searchChunks(Long sessionId, String query, int topK) {
        throw new UnsupportedOperationException("M1: 由协作者实现（向量检索）");
    }

    @Override
    public long countParsed(Long sessionId) {
        throw new UnsupportedOperationException("M1: 由协作者实现");
    }
}
