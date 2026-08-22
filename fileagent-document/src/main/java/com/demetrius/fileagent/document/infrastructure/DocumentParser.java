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
}
