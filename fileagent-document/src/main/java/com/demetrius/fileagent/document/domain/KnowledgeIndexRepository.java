package com.demetrius.fileagent.document.domain;

import java.util.List;

/**
 * 知识索引写入端口。
 *
 * @author raosaijie
 */
public interface KnowledgeIndexRepository {

    void saveAll(List<KnowledgeChunk> chunks);

    void deleteByFileId(Long fileId);

    /**
     * 按 chunkId 列表读取知识片段。
     * 实现方需保持请求顺序回排，并排除 embedding 等重字段。
     */
    List<KnowledgeChunk> findByChunkIds(List<String> chunkIds);
}
