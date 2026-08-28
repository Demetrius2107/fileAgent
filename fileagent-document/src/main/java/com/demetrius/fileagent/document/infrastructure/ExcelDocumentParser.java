package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * XLSX 解析器：遍历每个 sheet 的每行，把一行序列化为一个 chunk（保留表格结构）。
 * 仅支持 OOXML（.xlsx），旧版 .xls 属 M2。
 */
@Slf4j
@Component
public class ExcelDocumentParser implements DocumentParser {

    private static final String MIME_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final DataFormatter FORMATTER = new DataFormatter();

    @Override
    public boolean supports(String mimeType) {
        return MIME_XLSX.equalsIgnoreCase(mimeType);
    }

    @Override
    public List<String> parse(Path file, String mimeType) {
        List<String> chunks = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file);
             XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (Sheet sheet : workbook) {
                String sheetName = sheet.getSheetName();
                for (Row row : sheet) {
                    String line = serializeRow(sheetName, row, evaluator);
                    if (!line.isBlank()) {
                        chunks.add(line);
                    }
                }
            }
            return chunks;
        } catch (IOException e) {
            log.error("解析 XLSX 失败: {}", file, e);
            throw new BizException("XLSX 解析失败: " + file.getFileName());
        }
    }

    private String serializeRow(String sheetName, Row row, FormulaEvaluator evaluator) {
        StringBuilder sb = new StringBuilder();
        Iterator<Cell> cells = row.cellIterator();
        while (cells.hasNext()) {
            String value = FORMATTER.formatCellValue(cells.next(), evaluator).trim();
            if (!value.isEmpty()) {
                sb.append(value).append(" | ");
            }
        }
        return sb.isEmpty() ? "" : "[" + sheetName + "] " + sb.substring(0, sb.length() - 3);
    }

    @Override
    public DocumentMetadata extractMetadata(Path file, String mimeType) {
        try (InputStream in = Files.newInputStream(file);
             XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            int rowCount = 0;
            for (Sheet sheet : workbook) {
                rowCount += sheet.getLastRowNum() + 1;
            }
            return new DocumentMetadata(null, null, null, workbook.getNumberOfSheets());
        } catch (IOException e) {
            log.error("抽取 XLSX 元数据失败: {}", file, e);
            return DocumentMetadata.empty();
        }
    }
}
