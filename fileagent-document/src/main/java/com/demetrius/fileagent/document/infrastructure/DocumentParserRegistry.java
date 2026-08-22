package com.demetrius.fileagent.document.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 文档解析器注册表：按 MIME 类型分发到具体 {@link DocumentParser} 实现。
 */
@Component
@RequiredArgsConstructor
public class DocumentParserRegistry {

    private final List<DocumentParser> parsers;

    public Optional<DocumentParser> findParser(String mimeType) {
        return parsers.stream()
                .filter(parser -> parser.supports(mimeType))
                .findFirst();
    }
}
