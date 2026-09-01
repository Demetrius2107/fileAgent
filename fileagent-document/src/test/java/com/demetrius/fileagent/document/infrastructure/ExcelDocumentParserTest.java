package com.demetrius.fileagent.document.infrastructure;

import com.demetrius.fileagent.document.domain.ParsedChunk;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExcelDocumentParser} 通用表格结构解析测试。
 *
 * @author raosaijie
 */
class ExcelDocumentParserTest {

    private static final String MIME_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @TempDir
    Path tempDir;

    @Test
    void parseShouldUseFormulaResultInsteadOfFormulaSource() throws Exception {
        Path file = tempDir.resolve("目标.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var row = workbook.createSheet("目标").createRow(0);
            row.createCell(0).setCellValue(1);
            row.createCell(1).setCellValue(2);
            row.createCell(2).setCellFormula("SUM(A1:B1)");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            write(workbook, file);
        }

        List<String> chunks = new ExcelDocumentParser().parse(file, MIME_XLSX);

        assertThat(chunks).containsExactly("[目标] A: 1 | B: 2 | C: 3");
        assertThat(chunks.getFirst()).doesNotContain("SUM(");
    }

    @Test
    void parseChunksShouldDetectHeadersAndKeepGenericLocationMetadata() throws Exception {
        Path file = tempDir.resolve("项目.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("项目");
            addRow(sheet, 0, "姓名", "目标", "权重");
            addRow(sheet, 1, "张三", "完成系统升级", "40%");
            write(workbook, file);
        }

        List<ParsedChunk> chunks = new ExcelDocumentParser().parseChunks(file, MIME_XLSX);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().content())
                .contains("姓名: 张三", "目标: 完成系统升级", "权重: 40%");
        assertThat(chunks.getFirst().metadata())
                .containsEntry("sourceType", "xlsx")
                .containsEntry("sheetName", "项目")
                .containsEntry("rowIndex", 2)
                .containsEntry("sectionId", "sheet-0-section-0")
                .containsEntry("parentId", "sheet-0-section-0");
    }

    @Test
    void parseChunksShouldSplitSectionsOnEmptyRows() throws Exception {
        Path file = tempDir.resolve("多区域.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("计划");
            addRow(sheet, 0, "事项", "负责人");
            addRow(sheet, 1, "上线", "张三");
            sheet.createRow(2);
            addRow(sheet, 3, "风险", "等级");
            addRow(sheet, 4, "延期", "高");
            write(workbook, file);
        }

        List<ParsedChunk> chunks = new ExcelDocumentParser().parseChunks(file, MIME_XLSX);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).metadata()).containsEntry("sectionId", "sheet-0-section-0");
        assertThat(chunks.get(1).metadata()).containsEntry("sectionId", "sheet-0-section-1");
        assertThat(chunks).extracting(ParsedChunk::content)
                .containsExactly("[计划] 事项: 上线 | 负责人: 张三", "[计划] 风险: 延期 | 等级: 高");
    }

    @Test
    void parseChunksShouldMergeTwoHeaderRows() throws Exception {
        Path file = tempDir.resolve("多表头.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("人员");
            addRow(sheet, 0, "员工信息", "", "工作信息");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));
            addRow(sheet, 1, "姓名", "部门", "目标");
            addRow(sheet, 2, "张三", "研发", "完成升级");
            write(workbook, file);
        }

        List<ParsedChunk> chunks = new ExcelDocumentParser().parseChunks(file, MIME_XLSX);

        assertThat(chunks).extracting(ParsedChunk::content).containsExactly(
                "[人员] 员工信息 / 姓名: 张三 | 员工信息 / 部门: 研发 | 工作信息 / 目标: 完成升级");
        assertThat(chunks.getFirst().metadata()).containsEntry("rowIndex", 3);
    }

    @Test
    void parseChunksShouldNotRepeatHorizontallyMergedDataCells() throws Exception {
        Path file = tempDir.resolve("合并数据.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("评价");
            addRow(sheet, 0, "标题", "说明", "备注");
            addRow(sheet, 1, "结果导向", "", "");
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 2));
            write(workbook, file);
        }

        List<ParsedChunk> chunks = new ExcelDocumentParser().parseChunks(file, MIME_XLSX);

        assertThat(chunks).extracting(ParsedChunk::content)
                .containsExactly("[评价] 标题: 结果导向");
    }

    @Test
    void parseChunksShouldUseColumnCoordinatesWhenNoHeaderIsReliable() throws Exception {
        Path file = tempDir.resolve("数值.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("数据");
            addRow(sheet, 0, "100", "200");
            addRow(sheet, 1, "300", "400");
            write(workbook, file);
        }

        List<ParsedChunk> chunks = new ExcelDocumentParser().parseChunks(file, MIME_XLSX);

        assertThat(chunks).extracting(ParsedChunk::content).containsExactly(
                "[数据] A: 100 | B: 200",
                "[数据] A: 300 | B: 400");
    }

    @Test
    void parseChunksShouldKeepSingleColumnRowsAndPlaceholderLikeValues() throws Exception {
        Path file = tempDir.resolve("单列.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("EX.OKR");
            addRow(sheet, 0, "O1 XXXXXXX");
            addRow(sheet, 1, "编码 XXXX-1001");
            write(workbook, file);
        }

        List<ParsedChunk> chunks = new ExcelDocumentParser().parseChunks(file, MIME_XLSX);

        assertThat(chunks).extracting(ParsedChunk::content).containsExactly(
                "[EX.OKR] A: O1 XXXXXXX",
                "[EX.OKR] A: 编码 XXXX-1001");
    }

    @Test
    void parseChunksShouldSkipHiddenSheets() throws Exception {
        Path file = tempDir.resolve("隐藏.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            addRow(workbook.createSheet("可见"), 0, "可见内容");
            addRow(workbook.createSheet("隐藏"), 0, "隐藏内容");
            workbook.setSheetHidden(1, true);
            write(workbook, file);
        }

        List<ParsedChunk> chunks = new ExcelDocumentParser().parseChunks(file, MIME_XLSX);

        assertThat(chunks).extracting(ParsedChunk::content)
                .containsExactly("[可见] A: 可见内容");
    }

    private void addRow(org.apache.poi.ss.usermodel.Sheet sheet, int rowIndex, String... values) {
        var row = sheet.createRow(rowIndex);
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i]);
        }
    }

    private void write(XSSFWorkbook workbook, Path file) throws Exception {
        try (OutputStream out = Files.newOutputStream(file)) {
            workbook.write(out);
        }
    }
}
