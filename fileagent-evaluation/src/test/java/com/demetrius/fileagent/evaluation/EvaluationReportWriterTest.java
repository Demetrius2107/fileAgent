package com.demetrius.fileagent.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationReportWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteJsonAndReadableMarkdownReports() throws Exception {
        EvaluationReport report = new EvaluationReport("1.0", "v1", "2026-09-07T10:00:00Z",
                Map.of("gitCommit", "abc"), List.of(1, 5), 1, 1, 0,
                Map.of("mrr", 0.75, "recall@5", 1.0), Map.of("mrr", 1, "recall@5", 1),
                List.of(new EvaluationReport.CaseResult("case-1", "FACT", "问题",
                        Map.of("mrr", 0.75), 3, 12L, null)),
                new EvaluationReport.GateResult(true, List.of()));

        new EvaluationReportWriter(new ObjectMapper()).write(tempDir, report);

        assertThat(tempDir.resolve("report.json")).exists();
        assertThat(Files.readString(tempDir.resolve("report.md")))
                .contains("# RAG 评测报告")
                .contains("recall@5")
                .contains("PASS")
                .contains("case-1");
    }
}
