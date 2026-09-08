package com.demetrius.fileagent.evaluation;

import com.demetrius.fileagent.api.port.KnowledgeSearchPort;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在已部署实例内使用当前检索配置执行固定数据集评测。
 *
 * @author raosaijie
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "fileagent.evaluation.endpoint", name = "enabled", havingValue = "true")
public class EvaluationEndpointService {

    private static final int MAX_CASES = 100;
    private static final String VERSION_PATTERN = "[a-zA-Z0-9._-]+";

    private final KnowledgeSearchPort knowledgeSearchPort;
    private final ObjectMapper objectMapper;
    private final ResourcePatternResolver resourcePatternResolver;
    private final Environment environment;

    public EvaluationRunResponse run(EvaluationRunRequest request) {
        EvaluationRunRequest actualRequest = request == null ? EvaluationRunRequest.defaults() : request;
        String datasetVersion = validateDatasetVersion(actualRequest.datasetVersion());
        EvaluationFiles files = new EvaluationFiles(objectMapper);
        List<EvaluationCase> cases = files.loadCases(findCaseResources(datasetVersion));
        if (cases.size() > MAX_CASES) {
            throw new IllegalArgumentException("单次评测题目不能超过 " + MAX_CASES + " 道");
        }

        List<EvaluationObservation> observations = new EvaluationRunner(knowledgeSearchPort).collect(cases);
        EvaluationReport report = new EvaluationEngine().evaluate(datasetVersion, cases, observations,
                actualRequest.kValues(), runtimeMetadata(actualRequest.metadata()));
        QualityGateConfig gate = files.loadGateConfig(resource(
                "classpath:evaluation/" + datasetVersion + "/gate.json"));
        report = report.withGate(new QualityGateEvaluator().evaluate(report, gate, actualRequest.baseline()));
        return new EvaluationRunResponse(report, observations, EvaluationReportWriter.toMarkdown(report));
    }

    private Resource[] findCaseResources(String datasetVersion) {
        try {
            Resource[] resources = resourcePatternResolver.getResources(
                    "classpath*:evaluation/" + datasetVersion + "/cases/*.jsonl");
            if (resources.length == 0) {
                throw new IllegalArgumentException("找不到评测数据集: " + datasetVersion);
            }
            return resources;
        } catch (IOException e) {
            throw new IllegalStateException("读取评测数据集失败: " + datasetVersion, e);
        }
    }

    private Resource resource(String location) {
        Resource resource = resourcePatternResolver.getResource(location);
        if (!resource.exists()) {
            throw new IllegalArgumentException("评测资源不存在: " + location);
        }
        return resource;
    }

    private Map<String, String> runtimeMetadata(Map<String, String> requestedMetadata) {
        Map<String, String> metadata = new LinkedHashMap<>(requestedMetadata);
        addProperty(metadata, "embeddingModel", "spring.ai.openai.embedding.model");
        addProperty(metadata, "embeddingDimensions", "fileagent.elasticsearch.dimensions");
        addProperty(metadata, "indexAlias", "fileagent.elasticsearch.index-alias");
        addProperty(metadata, "physicalIndex", "fileagent.elasticsearch.physical-index");
        addProperty(metadata, "bm25TopK", "fileagent.elasticsearch.bm25-top-k");
        addProperty(metadata, "knnTopK", "fileagent.elasticsearch.knn-top-k");
        addProperty(metadata, "rrfRankConstant", "fileagent.elasticsearch.rrf-rank-constant");
        addProperty(metadata, "finalTopK", "fileagent.elasticsearch.final-top-k");
        addProperty(metadata, "rerankerEnabled", "fileagent.reranker.enabled");
        if (Boolean.parseBoolean(metadata.get("rerankerEnabled"))) {
            addProperty(metadata, "rerankerModel", "fileagent.reranker.model");
        }
        String gitCommit = environment.getProperty("GITHUB_SHA");
        if (gitCommit != null && !gitCommit.isBlank()) {
            metadata.put("gitCommit", gitCommit);
        }
        return metadata;
    }

    private void addProperty(Map<String, String> metadata, String metadataName, String propertyName) {
        String value = environment.getProperty(propertyName);
        if (value != null && !value.isBlank()) {
            metadata.put(metadataName, value);
        }
    }

    private static String validateDatasetVersion(String datasetVersion) {
        if (!datasetVersion.matches(VERSION_PATTERN)) {
            throw new IllegalArgumentException("datasetVersion 格式非法");
        }
        return datasetVersion;
    }
}
