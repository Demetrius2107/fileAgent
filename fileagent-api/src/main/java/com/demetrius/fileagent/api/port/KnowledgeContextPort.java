package com.demetrius.fileagent.api.port;

import java.util.List;

/**
 * 知识片段上下文端口（由 fileagent-document 的 infrastructure 实现）。
 * <p>
 * 供 Agent 的 {@code read_document_context} 工具按已授权 chunkId 读取正文与来源元数据。
 * 本端口不校验授权（授权白名单由 Agent 工具层维护），只负责按 ID 取回内容。
 */
public interface KnowledgeContextPort {

    /**
     * 按 chunkId 列表读取知识片段（保持请求顺序，缺失的 ID 被跳过）。
     *
     * @param chunkIds 已授权 chunk ID 列表
     * @return 片段上下文列表
     */
    List<KnowledgeChunkContext> read(List<String> chunkIds);

    /** 片段上下文（含正文与可引用来源元数据）。 */
    record KnowledgeChunkContext(
            String chunkId,
            Long fileId,
            String filename,
            String content,
            String sheetName,
            String sectionId,
            int chunkIndex) {
    }
}
