package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * DOCX 解析器：按段落聚合文本为 chunk；以段落数近似反映文档体量。
 * 仅支持 OOXML（.docx），旧版 .doc 属 M2。
 */
@Slf4j
@Component
public class WordDocumentParser implements DocumentParser {

    private static final String MIME_DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Override
    public boolean supports(String mimeType) {
        return MIME_DOCX.equalsIgnoreCase(mimeType);
    }

    @Override
    public List<String> parse(Path file, String mimeType) {
        List<String> chunks = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file);
             XWPFDocument document = new XWPFDocument(in)) {
            StringBuilder buffer = new StringBuilder();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText();
                if (text == null || text.isBlank()) {
                    // 空段落作为自然分块边界
                    flush(buffer, chunks);
                    continue;
                }
                buffer.append(text.trim()).append('\n');
            }
            flush(buffer, chunks);
            return chunks;
        } catch (IOException e) {
            log.error("解析 DOCX 失败: {}", file, e);
            throw new BizException("DOCX 解析失败: " + file.getFileName());
        }
    }

    private void flush(StringBuilder buffer, List<String> chunks) {
        if (!buffer.isEmpty()) {
            chunks.add(buffer.toString().trim());
            buffer.setLength(0);
        }
    }

    @Override
    public DocumentMetadata extractMetadata(Path file, String mimeType) {
        try (InputStream in = Files.newInputStream(file);
             XWPFDocument document = new XWPFDocument(in)) {
            // DOCX 无可靠页数概念，此处以段落总数近似反映文档体量
            int paragraphCount = document.getParagraphs().size();
            return new DocumentMetadata(null, null, paragraphCount, null);
        } catch (IOException e) {
            log.error("抽取 DOCX 元数据失败: {}", file, e);
            return DocumentMetadata.empty();
        }
    }
}
