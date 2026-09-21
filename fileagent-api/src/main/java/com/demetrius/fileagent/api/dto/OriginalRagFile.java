package com.demetrius.fileagent.api.dto;

/**
 * 知识库文件原件（预览/下载接口返回）
 */
public record OriginalRagFile(
        String filename,
        String mimeType,
        byte[] content
) {
}
