package com.demetrius.fileagent.api.port;

import com.demetrius.fileagent.api.dto.DocumentSummary;

import java.util.List;

/**
 * 文档域对外端口（由 fileagent-document 的 infrastructure 实现）。
 * 其它域检索文档片段 / 查询文档概要必须走本接口。
 */
public interface DocumentQueryPort {

    /** 会话下已解析文档概要 */
    List<DocumentSummary> listParsed(Long sessionId);

    /** RAG 检索：返回命中的文档片段 */
    List<ChunkHit> searchChunks(Long sessionId, String query, int topK);

    /** 会话下已成功解析的文档数（供上下文判断） */
    long countParsed(Long sessionId);

    record ChunkHit(String content, double score, Long documentId) {
    }
}
