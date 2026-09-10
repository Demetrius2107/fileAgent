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
        EvaluationCase evaluationCase = new EvaluationCase("1.0", "case-1", "FACT", List.of(), "年假有几天？",
                List.of(), new EvaluationCase.Filters("eval", "baseline", null),
                new EvaluationCase.Expected(true,
                        List.of(new EvaluationCase.ExpectedSource(null, null, "handbook.md", null,
                                null, null, 3)),
                        List.of("年假 5 天"), List.of("年假 3 天")));
        EvaluationObservation.ObservedSource source = new EvaluationObservation.ObservedSource(
                "1:0", 1L, "handbook.md", null, "document", null, 0,
                "员工入职第一年享有年假 5 天。", 0.95);
        EvaluationObservation observation = new EvaluationObservation("1.0", "case-1", List.of(source),
                "员工入职第一年享有年假 5 天。", false, List.of(source), Map.of(),
                Map.of("requiredFactCoverage", "年假 5 天：回答表达了相同事实"),
                "{\"decision\":\"ANSWERED\"}", 12L, null);
        EvaluationReport report = new EvaluationReport("1.0", "v1", "2026-09-07T10:00:00Z",
                Map.of("gitCommit", "abc"), List.of(1, 5), 1, 1, 0,
                Map.of("mrr", 0.75, "recall@5", 1.0, "requiredFactCoverage", 0.5),
                Map.of("mrr", 1, "recall@5", 1, "requiredFactCoverage", 1),
                List.of(new EvaluationReport.CaseResult("case-1", "FACT", "问题",
                        Map.of("mrr", 0.75, "requiredFactCoverage", 0.5), 3, 12L, null)),
                new EvaluationReport.GateResult(false,
                        List.of("requiredFactCoverage=0.5000 低于最低要求 0.7500")));
        QualityGateConfig gate = new QualityGateConfig("1.0",
                Map.of("requiredFactCoverage", 0.75), 0.03, List.of());

        new EvaluationReportWriter(new ObjectMapper()).write(tempDir, report,
                List.of(evaluationCase), List.of(observation), gate);

        assertThat(tempDir.resolve("report.json")).exists();
        assertThat(Files.readString(tempDir.resolve("report.md")))
                .contains("# RAG 端到端评测报告")
                .contains("recall@5")
                .contains("前 K 条结果覆盖了多少期望来源")
                .contains("## 待优化典型问题")
                .contains("汇总分数：0.5000，门禁要求：0.7500")
                .contains("低于门禁要求：1/1 道，展示其中 1 道")
                .contains("实际回答")
                .contains("Judge 理由：年假 5 天：回答表达了相同事实")
                .contains("员工入职第一年享有年假 5 天")
                .contains("期望答案要点")
                .contains("handbook.md")
                .contains("FAIL")
                .contains("case-1")
                .doesNotContain("## 单题结果")
                .doesNotContain("## 逐题详情")
                .doesNotContain("**本题分数**")
                .doesNotContain("**Top 3 检索片段**");
    }

    @Test
    void shouldLimitTypicalProblemsAndPreferDifferentCategories() {
        List<EvaluationCase> cases = List.of(
                evaluationCase("fact-1", "FACT"),
                evaluationCase("fact-2", "FACT"),
                evaluationCase("table-1", "TABLE"),
                evaluationCase("conflict-1", "CONFLICT"),
                evaluationCase("healthy-1", "FACT"));
        List<EvaluationObservation> observations = cases.stream()
                .map(item -> observation(item.id()))
                .toList();
        List<EvaluationReport.CaseResult> results = List.of(
                caseResult("fact-1", "FACT", 0.0),
                caseResult("fact-2", "FACT", 0.0),
                caseResult("table-1", "TABLE", 0.0),
                caseResult("conflict-1", "CONFLICT", 0.0),
                caseResult("healthy-1", "FACT", 1.0));
        EvaluationReport report = new EvaluationReport("1.0", "v1", "2026-09-07T10:00:00Z",
                Map.of(), List.of(5), 5, 5, 0,
                Map.of("requiredFactCoverage", 0.2), Map.of("requiredFactCoverage", 5), results,
                new EvaluationReport.GateResult(false,
                        List.of("requiredFactCoverage=0.2000 低于最低要求 0.7500")));
        QualityGateConfig gate = new QualityGateConfig("1.0",
                Map.of("requiredFactCoverage", 0.75), 0.03, List.of());

        String markdown = EvaluationReportWriter.toMarkdown(report, cases, observations, gate);

        assertThat(markdown)
                .contains("低于门禁要求：4/5 道，展示其中 3 道")
                .contains("#### fact-1")
                .contains("#### table-1")
                .contains("#### conflict-1")
                .doesNotContain("#### fact-2")
                .doesNotContain("#### healthy-1");
    }

    private static EvaluationCase evaluationCase(String id, String category) {
        return new EvaluationCase("1.0", id, category, List.of(), id,
                List.of(), new EvaluationCase.Filters("eval", "baseline", null),
                new EvaluationCase.Expected(true, List.of(), List.of("expected"), List.of()));
    }

    private static EvaluationObservation observation(String id) {
        return new EvaluationObservation("1.0", id, List.of(), "actual", false,
                List.of(), Map.of(), Map.of(), 1L, null);
    }

    private static EvaluationReport.CaseResult caseResult(String id, String category, double score) {
        return new EvaluationReport.CaseResult(id, category, id,
                Map.of("requiredFactCoverage", score), 0, 1L, null);
    }
}
