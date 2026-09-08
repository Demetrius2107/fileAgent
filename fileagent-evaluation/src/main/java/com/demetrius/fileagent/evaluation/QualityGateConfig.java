package com.demetrius.fileagent.evaluation;

import java.util.List;
import java.util.Map;

/**
 * 质量门禁阈值配置。
 *
 * @author raosaijie
 */
public record QualityGateConfig(
        String schemaVersion,
        Map<String, Double> minimumScores,
        Double maximumRegression,
        List<String> regressionMetrics
) {

    public QualityGateConfig {
        schemaVersion = schemaVersion == null ? "1.0" : schemaVersion;
        minimumScores = minimumScores == null ? Map.of() : Map.copyOf(minimumScores);
        maximumRegression = maximumRegression == null ? 0.0 : maximumRegression;
        regressionMetrics = regressionMetrics == null ? List.of() : List.copyOf(regressionMetrics);
        if (maximumRegression < 0.0) {
            throw new IllegalArgumentException("maximumRegression 不能小于 0");
        }
    }
}
