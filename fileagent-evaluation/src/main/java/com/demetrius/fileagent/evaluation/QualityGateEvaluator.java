package com.demetrius.fileagent.evaluation;

import java.util.ArrayList;
import java.util.List;

/**
 * 按绝对下限和相对 baseline 回退量执行质量门禁。
 *
 * @author raosaijie
 */
public final class QualityGateEvaluator {

    public EvaluationReport.GateResult evaluate(EvaluationReport current,
                                                QualityGateConfig config,
                                                EvaluationReport baseline) {
        List<String> violations = new ArrayList<>();
        config.minimumScores().forEach((metric, minimum) -> {
            Double actual = current.scores().get(metric);
            if (actual == null) {
                violations.add(metric + " 缺少结果，最低要求 " + format(minimum));
            } else if (actual < minimum) {
                violations.add(metric + "=" + format(actual) + " 低于最低要求 " + format(minimum));
            }
        });

        if (baseline != null) {
            if (!current.datasetVersion().equals(baseline.datasetVersion())) {
                violations.add("baseline 数据集版本不一致: current=" + current.datasetVersion()
                        + ", baseline=" + baseline.datasetVersion());
                return new EvaluationReport.GateResult(false, violations);
            }
            for (String metric : config.regressionMetrics()) {
                Double actual = current.scores().get(metric);
                Double previous = baseline.scores().get(metric);
                if (actual == null || previous == null) {
                    violations.add(metric + " 无法与 baseline 比较");
                } else if (previous - actual > config.maximumRegression()) {
                    violations.add(metric + " 相比 baseline 回退 " + format(previous - actual)
                            + "，允许回退 " + format(config.maximumRegression()));
                }
            }
        }
        return new EvaluationReport.GateResult(violations.isEmpty(), violations);
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }
}
