package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV 解析器：按行读取，每行一个 chunk。仅按逗号简单切分，不处理引号包裹的
 * 换行场景；内容级元数据无结构化信息，留给 chunkCount 反映行数。
 */
@Slf4j
@Component
public class CsvDocumentParser implements DocumentParser {

    private static final String MIME_CSV = "text/csv";

    @Override
    public boolean supports(String mimeType) {
        return MIME_CSV.equalsIgnoreCase(mimeType);
    }

    @Override
    public List<String> parse(Path file, String mimeType) {
        List<String> chunks = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    chunks.add(trimmed);
                }
            }
            return chunks;
        } catch (IOException e) {
            log.error("解析 CSV 失败: {}", file, e);
            throw new BizException("CSV 解析失败: " + file.getFileName());
        }
    }
}
