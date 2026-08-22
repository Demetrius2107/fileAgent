package com.demetrius.fileagent.api.dto;

/**
 * 上传文件响应
 */
public record UploadFileResp(
        Long documentId,
        String filename,
        Long size,
        String mimeType,
        String parseStatus,
        Integer chunkCount
) {
}
