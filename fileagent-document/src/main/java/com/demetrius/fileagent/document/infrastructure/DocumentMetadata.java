package com.demetrius.fileagent.document.infrastructure;

/**
 * 文档内容级元数据：由解析器从文件内容中抽取，落库前由编排层映射到
 * {@code DocumentEntity} 的离散字段。各格式按能取到的字段填充，取不到留 null。
 *
 * @author Demetrius
 * @since 0.1.0
 * @date 2026-08-22
 */
public record DocumentMetadata(
        String title,
        String author,
        Integer pageCount,
        Integer sheetCount
) {

    /** 无内容级元数据可抽取时（如纯文本）返回的空实例。 */
    public static DocumentMetadata empty() {
        return new DocumentMetadata(null, null, null, null);
    }
}
