package com.demetrius.fileagent.document.infrastructure;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExcelDocumentParser} 解析测试：公式单元格使用计算结果，避免公式源码污染知识片段。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-08-28
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
            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        }

        List<String> chunks = new ExcelDocumentParser().parse(file, MIME_XLSX);

        assertThat(chunks).containsExactly("[目标] 1 | 2 | 3");
        assertThat(chunks.getFirst()).doesNotContain("SUM(");
    }
}
