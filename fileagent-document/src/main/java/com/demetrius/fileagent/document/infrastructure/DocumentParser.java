package com.demetrius.fileagent.document.infrastructure;

import java.nio.file.Path;
import java.util.List;

/**
 * 文档解析器契约（M1 仅 TEXT/MD；M2 扩展 PDF/Office/OCR）。
 * 实现类放到 {@code infrastructure}，按 MIME 由 ParserRegistry 分发。
 */
public interface DocumentParser {

    /** 是否支持该 MIME 类型 */
    boolean supports(String mimeType);

    /** 解析为若干文本块（chunk） */
    List<String> parse(Path file, String mimeType);

    /**
     * 从文件内容抽取内容级元数据（标题/作者/页数等）。默认返回空，
     * 仅结构化格式（PDF/Office）的解析器覆盖此方法。
     */
    default DocumentMetadata extractMetadata(Path file, String mimeType) {
        return DocumentMetadata.empty();
    }
}
