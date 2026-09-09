package com.demetrius.fileagent.evaluation;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 输出 JSON 原始报告与聚焦门禁失败问题的 Markdown 摘要。
 *
 * @author raosaijie
 */
public final class EvaluationReportWriter {

    private static final int MAX_TYPICAL_CASES = 3;

    private final ObjectMapper objectMapper;

    public EvaluationReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(Path outputDirectory, EvaluationReport report) {
        write(outputDirectory, report, List.of(), List.of());
    }

    public void write(Path outputDirectory,
                      EvaluationReport report,
                      List<EvaluationCase> cases,
                      List<EvaluationObservation> observations) {
        write(outputDirectory, report, cases, observations, null);
    }

    public void write(Path outputDirectory,
                      EvaluationReport report,
                      List<EvaluationCase> cases,
                      List<EvaluationObservation> observations,
                      QualityGateConfig gateConfig) {
        try {
            Files.createDirectories(outputDirectory);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(outputDirectory.resolve("report.json").toFile(), report);
            Files.writeString(outputDirectory.resolve("report.md"),
                    toMarkdown(report, cases, observations, gateConfig), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("写入评测报告失败: " + outputDirectory, e);
        }
    }

    public static String toMarkdown(EvaluationReport report) {
        return toMarkdown(report, List.of(), List.of());
    }

    public static String toMarkdown(EvaluationReport report,
                                    List<EvaluationCase> cases,
                                    List<EvaluationObservation> observations) {
        return toMarkdown(report, cases, observations, null);
    }

    public static String toMarkdown(EvaluationReport report,
                                    List<EvaluationCase> cases,
                                    List<EvaluationObservation> observations,
                                    QualityGateConfig gateConfig) {
        StringBuilder content = new StringBuilder("# RAG 端到端评测报告\n\n");
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
        content.append("所有分数范围都是 0～1，越接近 1 越好。样本数表示该指标实际参与计算的题数；")
                .append("`@K` 表示只看检索结果的前 K 条。\n\n");
        content.append("| 指标 | 怎么看 | 分数 | 样本数 |\n|---|---|---:|---:|\n");
        report.scores().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> content.append("| ").append(entry.getKey()).append(" | ")
                        .append(metricDescription(entry.getKey())).append(" | ")
                        .append(format(entry.getValue())).append(" | ")
                        .append(report.sampleCounts().getOrDefault(entry.getKey(), 0)).append(" |\n"));

        if (report.gate() != null) {
            content.append("\n## 质量门禁\n\n");
            content.append(report.gate().passed() ? "PASS\n" : "FAIL\n");
            report.gate().violations().forEach(violation -> content.append("- ").append(violation).append('\n'));
        }

        appendTypicalProblems(content, report, cases, observations, gateConfig);
        return content.toString();
    }

    private static void appendTypicalProblems(StringBuilder content,
                                              EvaluationReport report,
                                              List<EvaluationCase> cases,
                                              List<EvaluationObservation> observations,
                                              QualityGateConfig gateConfig) {
        content.append("\n## 待优化典型问题\n\n");
        if (report.gate() == null || report.gate().passed()) {
            content.append("质量门禁已通过，没有需要重点展示的问题。\n");
            return;
        }
        content.append("这里只展示未通过门禁指标的代表问题，每项最多 3 道；完整逐题数据见 ")
                .append("`report.json` 和 `observations.jsonl`。\n\n");
        if (cases.isEmpty() || observations.isEmpty()) {
            content.append("缺少评测题或观测数据，无法生成代表问题。\n");
            return;
        }

        Map<String, EvaluationCase> casesById = cases.stream()
                .collect(Collectors.toMap(EvaluationCase::id, Function.identity()));
        Map<String, EvaluationObservation> observationsById = observations.stream()
                .collect(Collectors.toMap(EvaluationObservation::caseId, Function.identity()));
        List<String> failedMetrics = failedMetrics(report);
        if (failedMetrics.isEmpty()) {
            content.append("当前失败原因不对应可下钻的单题指标，请直接查看质量门禁提示。\n");
            return;
        }

        for (String metric : failedMetrics) {
            Double configuredThreshold = gateConfig == null ? null : gateConfig.minimumScores().get(metric);
            Double threshold = configuredThreshold != null && report.scores().get(metric) < configuredThreshold
                    ? configuredThreshold : null;
            List<EvaluationReport.CaseResult> candidates = failedCases(report, metric, threshold);
            List<EvaluationReport.CaseResult> typicalCases = selectTypicalCases(candidates);
            content.append("### ").append(metric).append("\n\n");
            content.append("- 汇总分数：").append(format(report.scores().get(metric)));
            if (threshold != null) {
                content.append("，门禁要求：").append(format(threshold));
            }
            content.append('\n');
            if (threshold != null) {
                content.append("- 低于门禁要求：").append(candidates.size()).append('/')
                        .append(report.sampleCounts().getOrDefault(metric, 0)).append(" 道，展示其中 ")
                        .append(typicalCases.size()).append(" 道\n\n");
            } else {
                content.append("- 展示该指标分数最低的 ").append(typicalCases.size()).append(" 道题\n\n");
            }
            if (typicalCases.isEmpty()) {
                content.append("没有可展示的单题评分数据。\n\n");
                continue;
            }
            typicalCases.forEach(result -> appendTypicalCase(content, metric, threshold, result,
                    casesById.get(result.caseId()), observationsById.get(result.caseId())));
        }
    }

    private static List<String> failedMetrics(EvaluationReport report) {
        Set<String> metrics = new LinkedHashSet<>();
        for (String violation : report.gate().violations()) {
            report.scores().keySet().stream()
                    .filter(metric -> violation.startsWith(metric + "=") || violation.startsWith(metric + " "))
                    .findFirst()
                    .ifPresent(metrics::add);
        }
        return List.copyOf(metrics);
    }

    private static List<EvaluationReport.CaseResult> failedCases(EvaluationReport report,
                                                                 String metric,
                                                                 Double threshold) {
        if ("caseSuccessRate".equals(metric)) {
            return report.cases().stream()
                    .filter(result -> result.error() != null)
                    .sorted(Comparator.comparing(EvaluationReport.CaseResult::caseId))
                    .toList();
        }
        List<EvaluationReport.CaseResult> scoredCases = report.cases().stream()
                .filter(result -> result.scores().containsKey(metric))
                .sorted(Comparator.comparingDouble((EvaluationReport.CaseResult result) -> result.scores().get(metric))
                        .thenComparing(EvaluationReport.CaseResult::caseId))
                .toList();
        if (threshold == null) {
            return scoredCases;
        }
        return scoredCases.stream()
                .filter(result -> result.scores().get(metric) < threshold)
                .toList();
    }

    private static List<EvaluationReport.CaseResult> selectTypicalCases(
            List<EvaluationReport.CaseResult> candidates) {
        List<EvaluationReport.CaseResult> selected = new ArrayList<>(MAX_TYPICAL_CASES);
        Set<String> selectedCategories = new LinkedHashSet<>();
        for (EvaluationReport.CaseResult candidate : candidates) {
            if (selectedCategories.add(candidate.category())) {
                selected.add(candidate);
                if (selected.size() == MAX_TYPICAL_CASES) {
                    return selected;
                }
            }
        }
        for (EvaluationReport.CaseResult candidate : candidates) {
            if (!selected.contains(candidate)) {
                selected.add(candidate);
                if (selected.size() == MAX_TYPICAL_CASES) {
                    break;
                }
            }
        }
        return selected;
    }

    private static void appendTypicalCase(StringBuilder content,
                                          String metric,
                                          Double threshold,
                                          EvaluationReport.CaseResult result,
                                          EvaluationCase evaluationCase,
                                          EvaluationObservation observation) {
        content.append("#### ").append(escapeText(result.caseId())).append(" · ")
                .append(escapeText(result.question())).append("\n\n");
        Double score = result.scores().get(metric);
        if (score != null) {
            content.append("- 本题分数：").append(format(score));
            if (threshold != null) {
                content.append("（要求不低于 ").append(format(threshold)).append('）');
            }
            content.append('\n');
        }
        String judgeReason = observation == null ? null : observation.judgeReasons().get(metric);
        if (judgeReason != null && !judgeReason.isBlank()) {
            content.append("- Judge 理由：").append(escapeText(judgeReason)).append('\n');
        }
        if (evaluationCase != null) {
            content.append("- 期望决策：")
                    .append(evaluationCase.expected().shouldAnswer() ? "应回答" : "应拒答/说明资料不足")
                    .append('\n');
            content.append("- 期望答案要点：")
                    .append(joinOrNone(evaluationCase.expected().requiredFacts())).append('\n');
            if ("forbiddenFactSafety".equals(metric)) {
                content.append("- 禁止出现的错误事实：")
                        .append(joinOrNone(evaluationCase.expected().forbiddenFacts())).append('\n');
            }
        }
        if (observation == null) {
            content.append("- 实际结果：缺少观测值\n\n");
            return;
        }
        content.append("- 实际决策：").append(actualDecision(observation)).append('\n');
        content.append("- 返回来源：").append(sourceNames(observation.citations())).append('\n');
        content.append("- 总耗时：").append(observation.durationMs()).append(" ms\n\n");

        content.append("**实际回答**\n\n");
        if (observation.answer() == null || observation.answer().isBlank()) {
            content.append("> （没有生成回答）\n\n");
        } else {
            appendBlockquote(content, observation.answer());
        }

        content.append("**Top 1 检索片段**\n\n");
        if (observation.retrieved().isEmpty()) {
            content.append("未检索到片段。\n\n");
        } else {
            EvaluationObservation.ObservedSource source = observation.retrieved().getFirst();
            content.append("- 来源：").append(escapeText(valueOrDash(source.filename())))
                    .append("，Chunk：").append(escapeText(valueOrDash(source.chunkId())))
                    .append("，分数：").append(source.score() == null ? "-" : format(source.score())).append("\n\n");
            content.append("> ").append(escapeText(preview(source.content()))).append("\n\n");
        }
        if (result.error() != null) {
            content.append("**错误：** ").append(escapeText(result.error())).append("\n\n");
        }
    }

    private static String metricDescription(String metric) {
        if (metric.startsWith("hitRate@")) {
            return "前 K 条中至少命中一个期望来源的题目比例";
        }
        if (metric.startsWith("recall@")) {
            return "前 K 条结果覆盖了多少期望来源";
        }
        if (metric.startsWith("precision@")) {
            return "前 K 条结果中有多少属于期望来源";
        }
        if (metric.startsWith("ndcg@")) {
            return "综合相关性等级与排序位置，相关来源越靠前越好";
        }
        if (metric.startsWith("judge.")) {
            return "外部 Judge 给出的专项评分";
        }
        return switch (metric) {
            case "caseSuccessRate" -> "评测流程未报错的比例，不等于答案正确率";
            case "mrr" -> "第一个相关来源的平均倒数排名，越早出现越接近 1";
            case "noAnswerEmptyRetrieval" -> "无答案题完全没有召回内容的比例";
            case "requiredFactCoverage" -> "实际回答覆盖期望答案要点的比例";
            case "forbiddenFactSafety" -> "实际回答没有出现已知错误事实的比例";
            case "answerDecisionAccuracy" -> "该回答或该拒答的决策是否符合预期";
            case "unsupportedClaimSafety" -> "回答没有包含证据不支持的具体结论、数值或规则";
            case "citationPrecision" -> "返回来源中真正属于期望来源的比例";
            case "citationRecall" -> "期望来源中被实际返回来源覆盖的比例";
            default -> "自定义评测指标，越接近 1 越好";
        };
    }

    private static void appendBlockquote(StringBuilder content, String value) {
        Arrays.stream(value.split("\\R", -1))
                .forEach(line -> content.append("> ").append(escapeText(line)).append('\n'));
        content.append('\n');
    }

    private static String sourceNames(List<EvaluationObservation.ObservedSource> sources) {
        List<String> filenames = sources.stream()
                .map(EvaluationObservation.ObservedSource::filename)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .map(EvaluationReportWriter::escapeText)
                .toList();
        return filenames.isEmpty() ? "无" : String.join("、", filenames);
    }

    private static String actualDecision(EvaluationObservation observation) {
        if (observation.error() != null && !observation.error().isBlank()) {
            return "执行失败";
        }
        return Boolean.TRUE.equals(observation.refused()) ? "拒答/资料不足" : "已回答";
    }

    private static String joinOrNone(List<String> values) {
        return values.isEmpty() ? "无" : values.stream()
                .map(EvaluationReportWriter::escapeText)
                .collect(Collectors.joining("；"));
    }

    private static String preview(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 180 ? normalized : normalized.substring(0, 180) + "...";
    }

    private static String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String escapeText(String value) {
        return value == null ? "" : value.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
