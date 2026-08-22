package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 纯文本解析器（TEXT / Markdown）。
 * 解析 + 分块一体：返回的每个元素即一个 chunk。
 * PDF / Office 解析器按里程碑 M2 扩展（经 ParserRegistry 分发）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TextDocumentParser implements DocumentParser {

    private static final Set<String> SUPPORTED_MIMES = Set.of(
            "text/plain", "text/markdown", "text/x-markdown");

    private final TextChunker textChunker;

    @Override
    public boolean supports(String mimeType) {
        return mimeType != null && SUPPORTED_MIMES.contains(mimeType.toLowerCase());
    }

    @Override
    public List<String> parse(Path file, String mimeType) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("读取文本文件失败: {}", file, e);
            throw new BizException("读取文件失败: " + file.getFileName());
        }
        return textChunker.chunk(content);
    }
}
