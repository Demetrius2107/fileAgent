package com.demetrius.fileagent.evaluation;

import java.util.List;
import java.util.Map;

/**
 * 部署实例执行 RAG 评测的请求。
 *
 * @author raosaijie
 */
public record EvaluationRunRequest(
        String datasetVersion,
        List<Integer> kValues,
        Map<String, String> metadata,
        EvaluationReport baseline
) {

    private static final int MAX_K = 100;
    private static final int MAX_K_VALUES = 10;

    public EvaluationRunRequest {
        datasetVersion = datasetVersion == null || datasetVersion.isBlank() ? "v1" : datasetVersion;
        kValues = kValues == null || kValues.isEmpty() ? List.of(1, 3, 5, 10) : List.copyOf(kValues);
        if (kValues.size() > MAX_K_VALUES
                || kValues.stream().anyMatch(k -> k == null || k <= 0 || k > MAX_K)) {
            throw new IllegalArgumentException("kValues 最多包含 10 个 1 到 100 之间的整数");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static EvaluationRunRequest defaults() {
        return new EvaluationRunRequest("v1", List.of(1, 3, 5, 10), Map.of(), null);
    }
}
