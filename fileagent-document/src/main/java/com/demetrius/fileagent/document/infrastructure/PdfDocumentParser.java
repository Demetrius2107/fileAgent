package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 解析器：按页抽取文本层，每页一个 chunk；从文档信息抽取标题/作者/页数元数据。
 * 仅覆盖文本层 PDF，扫描件 OCR 属 M2。
 */
@Slf4j
@Component
public class PdfDocumentParser implements DocumentParser {

    private static final String MIME_PDF = "application/pdf";

    @Override
    public boolean supports(String mimeType) {
        return MIME_PDF.equalsIgnoreCase(mimeType);
    }

    @Override
    public List<String> parse(Path file, String mimeType) {
        List<String> chunks = new ArrayList<>();
        try (PDDocument document = PDDocument.load(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document).trim();
                if (!text.isEmpty()) {
                    chunks.add(text);
                }
            }
            return chunks;
        } catch (IOException e) {
            log.error("解析 PDF 失败: {}", file, e);
            throw new BizException("PDF 解析失败: " + file.getFileName());
        }
    }

    @Override
    public DocumentMetadata extractMetadata(Path file, String mimeType) {
        try (PDDocument document = PDDocument.load(file.toFile())) {
            PDDocumentInformation info = document.getDocumentInformation();
            return new DocumentMetadata(
                    info == null ? null : blankToNull(info.getTitle()),
                    info == null ? null : blankToNull(info.getAuthor()),
                    document.getNumberOfPages(),
                    null
            );
        } catch (IOException e) {
            log.error("抽取 PDF 元数据失败: {}", file, e);
            return DocumentMetadata.empty();
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
