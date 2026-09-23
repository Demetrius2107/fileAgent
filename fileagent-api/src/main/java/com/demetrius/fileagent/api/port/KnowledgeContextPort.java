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

    /**
     * 按文件读取可精读的 CHILD 分块目录，不返回完整正文。
     *
     * @param fileId 文件 ID
     * @param afterChunkIndex 排他游标，首次读取传入负数
     * @param limit 本页大小，服务端最多返回 50 项
     * @return 文档目录页
     */
    DocumentOutlinePage outline(Long fileId, int afterChunkIndex, int limit);

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

    /** 文档目录分页结果。 */
    record DocumentOutlinePage(
            List<DocumentOutlineItem> items,
            Integer nextChunkIndex) {
    }

    /** 文档目录项，仅包含授权精读所需的元数据和短预览。 */
    record DocumentOutlineItem(
            String chunkId,
            String parentId,
            String filename,
            String sourceType,
            String sheetName,
            String sectionId,
            Integer rowIndex,
            int chunkIndex,
            String preview) {
    }
}
