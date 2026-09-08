package com.demetrius.fileagent.evaluation;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;

/**
 * 输出 JSON 原始报告与 Markdown 摘要。
 *
 * @author raosaijie
 */
public final class EvaluationReportWriter {

    private final ObjectMapper objectMapper;

    public EvaluationReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(Path outputDirectory, EvaluationReport report) {
        try {
            Files.createDirectories(outputDirectory);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(outputDirectory.resolve("report.json").toFile(), report);
            Files.writeString(outputDirectory.resolve("report.md"), toMarkdown(report), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("写入评测报告失败: " + outputDirectory, e);
        }
    }

    public static String toMarkdown(EvaluationReport report) {
        StringBuilder content = new StringBuilder("# RAG 评测报告\n\n");
        content.append("- 数据集版本：").append(report.datasetVersion()).append('\n');
        content.append("- 生成时间：").append(report.generatedAt()).append('\n');
        content.append("- 成功/总题数：").append(report.successfulCases()).append('/').append(report.totalCases()).append('\n');
        content.append("- 失败题数：").append(report.failedCases()).append("\n\n");

        if (!report.metadata().isEmpty()) {
            content.append("## 运行信息\n\n");
            report.metadata().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> content.append("- ").append(entry.getKey()).append("：")
                            .append(entry.getValue()).append('\n'));
            content.append('\n');
        }

        content.append("## 汇总指标\n\n");
        content.append("| 指标 | 分数 | 样本数 |\n|---|---:|---:|\n");
        report.scores().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> content.append("| ").append(entry.getKey()).append(" | ")
                        .append(format(entry.getValue())).append(" | ")
                        .append(report.sampleCounts().getOrDefault(entry.getKey(), 0)).append(" |\n"));

        if (report.gate() != null) {
            content.append("\n## 质量门禁\n\n");
            content.append(report.gate().passed() ? "PASS\n" : "FAIL\n");
            report.gate().violations().forEach(violation -> content.append("- ").append(violation).append('\n'));
        }

        content.append("\n## 单题结果\n\n");
        content.append("| ID | 分类 | 命中数 | 耗时(ms) | 状态 |\n|---|---|---:|---:|---|\n");
        report.cases().stream().sorted(Comparator.comparing(EvaluationReport.CaseResult::caseId))
                .forEach(result -> content.append("| ").append(result.caseId()).append(" | ")
                        .append(result.category()).append(" | ").append(result.retrievedCount()).append(" | ")
                        .append(result.durationMs()).append(" | ")
                        .append(result.error() == null ? "OK" : escape(result.error())).append(" |\n"));
        return content.toString();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String escape(String value) {
        return value.replace("|", "\\|").replace("\n", " ");
    }
}
