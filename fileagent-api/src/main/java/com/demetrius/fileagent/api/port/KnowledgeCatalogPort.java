package com.demetrius.fileagent.api.port;

import com.demetrius.fileagent.api.enums.ParseStatus;

import java.util.List;

/**
 * 知识目录端口（由 fileagent-document 的 infrastructure 实现）。
 * <p>
 * 供 Agent 的 {@code list_knowledge_files} 工具读取可检索知识文件概要。
 * 只返回文件级元数据，不返回文件存储路径、解析器细节或原始内容。
 */
public interface KnowledgeCatalogPort {

    /**
     * 列出符合范围的知识文件概要（仅索引成功的文件）。
     *
     * @param query 可信的过滤条件（ragName / knowledgeTag / limit）
     * @return 文件概要列表，按创建时间倒序，最多 {@code limit} 条
     */
    List<KnowledgeFile> list(Query query);

    /** 目录查询条件。limit 由服务端强制收敛到 1..20。 */
    record Query(String ragName, String knowledgeTag, int limit) {
    }

    /** 知识文件概要。 */
    record KnowledgeFile(
            Long fileId,
            String ragName,
            String knowledgeTag,
            String filename,
            ParseStatus status,
            int chunkCount) {
    }
}
