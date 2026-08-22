package com.demetrius.fileagent.api.dto;

/**
 * 上传文件响应
 *
 * @param title      内容级元数据：文档标题（解析时抽取，无则 null）
 * @param author     内容级元数据：作者（解析时抽取，无则 null）
 * @param pageCount  内容级元数据：页数/段落数（PDF/Word 解析时抽取，无则 null）
 * @param sheetCount 内容级元数据：工作表数（Excel 解析时抽取，无则 null）
 */
public record UploadFileResp(
        Long documentId,
        String filename,
        Long size,
        String mimeType,
        String parseStatus,
        Integer chunkCount,
        String title,
        String author,
        Integer pageCount,
        Integer sheetCount
) {
}
