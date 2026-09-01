package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.common.exception.BizException;
import com.demetrius.fileagent.document.domain.ParsedChunk;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * XLSX 通用解析器：识别连续表格区域和表头，将数据行转换为带位置元数据的知识块。
 *
 * @author raosaijie
 */
@Slf4j
@Component
public class ExcelDocumentParser implements DocumentParser {

    private static final String MIME_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final int MAX_HEADER_DEPTH = 2;
    private static final DataFormatter FORMATTER = new DataFormatter();

    @Override
    public boolean supports(String mimeType) {
        return MIME_XLSX.equalsIgnoreCase(mimeType);
    }

    @Override
    public List<String> parse(Path file, String mimeType) {
        return parseChunks(file, mimeType).stream()
                .map(ParsedChunk::content)
                .toList();
    }

    @Override
    public List<ParsedChunk> parseChunks(Path file, String mimeType) {
        List<ParsedChunk> chunks = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file);
             XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                if (workbook.isSheetHidden(sheetIndex) || workbook.isSheetVeryHidden(sheetIndex)) {
                    continue;
                }
                appendSheetChunks(chunks, workbook.getSheetAt(sheetIndex), sheetIndex, evaluator);
            }
            return List.copyOf(chunks);
        } catch (IOException e) {
            log.error("解析 XLSX 失败: {}", file, e);
            throw new BizException("XLSX 解析失败: " + file.getFileName());
        }
    }

    private void appendSheetChunks(List<ParsedChunk> chunks, Sheet sheet, int sheetIndex,
                                   FormulaEvaluator evaluator) {
        List<RowRegion> regions = splitRegions(readRows(sheet, evaluator));
        for (int sectionIndex = 0; sectionIndex < regions.size(); sectionIndex++) {
            RowRegion region = regions.get(sectionIndex);
            int headerDepth = detectHeaderDepth(region);
            List<String> headers = mergeHeaders(region, headerDepth);
            String sectionId = "sheet-" + sheetIndex + "-section-" + sectionIndex;
            for (int rowOffset = headerDepth; rowOffset < region.rows().size(); rowOffset++) {
                SheetRow row = region.rows().get(rowOffset);
                String content = serializeDataRow(sheet.getSheetName(), headers, row.values());
                if (content.isBlank()) {
                    continue;
                }
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("sourceType", "xlsx");
                metadata.put("sheetName", sheet.getSheetName());
                metadata.put("rowIndex", row.rowIndex() + 1);
                metadata.put("sectionId", sectionId);
                chunks.add(new ParsedChunk(content, metadata));
            }
        }
    }

    private List<SheetRow> readRows(Sheet sheet, FormulaEvaluator evaluator) {
        List<SheetRow> rows = new ArrayList<>();
        int lastRow = Math.max(sheet.getLastRowNum(), 0);
        for (int rowIndex = 0; rowIndex <= lastRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            int lastCell = lastCellIndex(sheet, rowIndex, row);
            List<String> values = new ArrayList<>(lastCell);
            for (int columnIndex = 0; columnIndex < lastCell; columnIndex++) {
                values.add(readCellValue(sheet, rowIndex, columnIndex, evaluator));
            }
            rows.add(new SheetRow(rowIndex, trimTrailingBlanks(values)));
        }
        return rows;
    }

    private int lastCellIndex(Sheet sheet, int rowIndex, Row row) {
        int lastCell = row == null ? 0 : Math.max(row.getLastCellNum(), 0);
        for (CellRangeAddress merged : sheet.getMergedRegions()) {
            if (rowIndex >= merged.getFirstRow() && rowIndex <= merged.getLastRow()) {
                lastCell = Math.max(lastCell, merged.getLastColumn() + 1);
            }
        }
        return lastCell;
    }

    private String readCellValue(Sheet sheet, int rowIndex, int columnIndex,
                                 FormulaEvaluator evaluator) {
        Row row = sheet.getRow(rowIndex);
        Cell cell = row == null ? null : row.getCell(columnIndex);
        String value = cell == null ? "" : FORMATTER.formatCellValue(cell, evaluator).trim();
        if (!value.isEmpty()) {
            return value;
        }
        for (CellRangeAddress merged : sheet.getMergedRegions()) {
            if (!merged.isInRange(rowIndex, columnIndex)) {
                continue;
            }
            Row firstRow = sheet.getRow(merged.getFirstRow());
            Cell firstCell = firstRow == null ? null : firstRow.getCell(merged.getFirstColumn());
            return firstCell == null ? "" : FORMATTER.formatCellValue(firstCell, evaluator).trim();
        }
        return "";
    }

    private List<String> trimTrailingBlanks(List<String> values) {
        int end = values.size();
        while (end > 0 && values.get(end - 1).isBlank()) {
            end--;
        }
        return List.copyOf(values.subList(0, end));
    }

    private List<RowRegion> splitRegions(List<SheetRow> rows) {
        List<RowRegion> regions = new ArrayList<>();
        List<SheetRow> current = new ArrayList<>();
        for (SheetRow row : rows) {
            if (row.isEmpty()) {
                if (!current.isEmpty()) {
                    regions.add(new RowRegion(List.copyOf(current)));
                    current.clear();
                }
                continue;
            }
            current.add(row);
        }
        if (!current.isEmpty()) {
            regions.add(new RowRegion(List.copyOf(current)));
        }
        return regions;
    }

    private int detectHeaderDepth(RowRegion region) {
        if (region.rows().size() < 2) {
            return 0;
        }
        SheetRow first = region.rows().get(0);
        if (!isHeaderCandidate(first, region.rows().get(1))) {
            return 0;
        }
        if (region.rows().size() >= 3
                && hasDuplicateValue(first.values())
                && isHeaderCandidate(region.rows().get(1), region.rows().get(2))) {
            return MAX_HEADER_DEPTH;
        }
        return 1;
    }

    private boolean isHeaderCandidate(SheetRow candidate, SheetRow following) {
        int nonEmpty = candidate.nonEmptyCount();
        if (nonEmpty < 2 || candidate.textCount() * 10 < nonEmpty * 6) {
            return false;
        }
        int covered = 0;
        for (int i = 0; i < candidate.values().size(); i++) {
            if (!candidate.values().get(i).isBlank()
                    && i < following.values().size()
                    && !following.values().get(i).isBlank()) {
                covered++;
            }
        }
        return covered >= (nonEmpty + 1) / 2;
    }

    private boolean hasDuplicateValue(List<String> values) {
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            if (!value.isBlank() && !unique.add(value)) {
                return true;
            }
        }
        return false;
    }

    private List<String> mergeHeaders(RowRegion region, int headerDepth) {
        int columnCount = region.rows().stream()
                .mapToInt(row -> row.values().size())
                .max()
                .orElse(0);
        List<String> headers = new ArrayList<>(columnCount);
        for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
            List<String> parts = new ArrayList<>(headerDepth);
            for (int headerIndex = 0; headerIndex < headerDepth; headerIndex++) {
                List<String> values = region.rows().get(headerIndex).values();
                if (columnIndex >= values.size()) {
                    continue;
                }
                String value = values.get(columnIndex);
                if (!value.isBlank() && (parts.isEmpty() || !parts.getLast().equals(value))) {
                    parts.add(value);
                }
            }
            headers.add(parts.isEmpty()
                    ? excelColumnName(columnIndex)
                    : String.join(" / ", parts));
        }
        return List.copyOf(headers);
    }

    private String serializeDataRow(String sheetName, List<String> headers, List<String> values) {
        List<String> fields = new ArrayList<>();
        for (int columnIndex = 0; columnIndex < values.size(); columnIndex++) {
            String value = values.get(columnIndex);
            if (value.isBlank()) {
                continue;
            }
            String header = columnIndex < headers.size()
                    ? headers.get(columnIndex)
                    : excelColumnName(columnIndex);
            fields.add(header + ": " + value);
        }
        return fields.isEmpty() ? "" : "[" + sheetName + "] " + String.join(" | ", fields);
    }

    private String excelColumnName(int columnIndex) {
        return CellReference.convertNumToColString(columnIndex);
    }

    @Override
    public DocumentMetadata extractMetadata(Path file, String mimeType) {
        try (InputStream in = Files.newInputStream(file);
             XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            return new DocumentMetadata(null, null, null, workbook.getNumberOfSheets());
        } catch (IOException e) {
            log.error("抽取 XLSX 元数据失败: {}", file, e);
            return DocumentMetadata.empty();
        }
    }

    private record SheetRow(int rowIndex, List<String> values) {

        private boolean isEmpty() {
            return nonEmptyCount() == 0;
        }

        private int nonEmptyCount() {
            return (int) values.stream().filter(value -> !value.isBlank()).count();
        }

        private int textCount() {
            return (int) values.stream()
                    .filter(value -> !value.isBlank())
                    .filter(ExcelDocumentParser::containsLetter)
                    .count();
        }
    }

    private record RowRegion(List<SheetRow> rows) {
    }

    private static boolean containsLetter(String value) {
        return value.codePoints().anyMatch(Character::isLetter);
    }
}
