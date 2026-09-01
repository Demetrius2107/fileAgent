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
}
