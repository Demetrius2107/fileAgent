package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * 文档解析器注册表：按 MIME 把解析任务路由给合适的 {@link DocumentParser} 实现。
 * 仅做路由分发，不含解析逻辑；无匹配解析器时抛出 BizException。
 *
 * @author Demetrius
 * @since 0.1.0
 * @date 2026-08-22
 */
@Component
@RequiredArgsConstructor
public class ParserRegistry {

    private final List<DocumentParser> parsers;

    /**
     * 是否存在支持该 MIME 的解析器（供编排层判断能否解析，避免无谓抛异常）。
     *
     * @param mimeType 文件 MIME 类型
     * @return 存在匹配解析器返回 true
     */
    public boolean supports(String mimeType) {
        return parsers.stream().anyMatch(p -> p.supports(mimeType));
    }

    /**
     * 解析文件为文本块。
     *
     * @throws BizException 当没有解析器支持该 MIME 时
     */
    public List<String> parse(Path file, String mimeType) {
        return parsers.stream()
                .filter(p -> p.supports(mimeType))
                .findFirst()
                .map(p -> p.parse(file, mimeType))
                .orElseThrow(() -> new BizException("暂不支持该文件类型: " + mimeType));
    }

    /**
     * 抽取文件内容级元数据。无匹配解析器或解析器未覆盖时返回空元数据。
     */
    public DocumentMetadata extractMetadata(Path file, String mimeType) {
        return parsers.stream()
                .filter(p -> p.supports(mimeType))
                .findFirst()
                .map(p -> p.extractMetadata(file, mimeType))
                .orElseGet(DocumentMetadata::empty);
    }
}
