package com.demetrius.fileagent.evaluation;

import tools.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 读写版本化评测数据文件。
 *
 * @author raosaijie
 */
public final class EvaluationFiles {

    public static final String SUPPORTED_SCHEMA_VERSION = "1.0";

    private final ObjectMapper objectMapper;

    public EvaluationFiles(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<EvaluationCase> loadCases(Path path) {
        List<EvaluationCase> cases = loadJsonLines(path, EvaluationCase.class);
        validateUniqueCases(cases);
        return cases;
    }

    public List<EvaluationCase> loadCases(Resource[] resources) {
        List<EvaluationCase> cases = new ArrayList<>();
        java.util.Arrays.stream(resources)
                .sorted(Comparator.comparing(Resource::getDescription))
                .forEach(resource -> cases.addAll(loadJsonLines(resource, EvaluationCase.class)));
        validateUniqueCases(cases);
        return cases;
    }

    private static void validateUniqueCases(List<EvaluationCase> cases) {
        Set<String> ids = new HashSet<>();
        for (EvaluationCase evaluationCase : cases) {
            requireSupportedVersion(evaluationCase.schemaVersion(), evaluationCase.id());
            if (!ids.add(evaluationCase.id())) {
                throw new IllegalArgumentException("评测题 id 重复: " + evaluationCase.id());
            }
        }
    }

    public List<EvaluationObservation> loadObservations(Path path) {
        List<EvaluationObservation> observations = loadJsonLines(path, EvaluationObservation.class);
        Set<String> ids = new HashSet<>();
        for (EvaluationObservation observation : observations) {
            requireSupportedVersion(observation.schemaVersion(), observation.caseId());
            if (!ids.add(observation.caseId())) {
                throw new IllegalArgumentException("观测值 caseId 重复: " + observation.caseId());
            }
        }
        return observations;
    }

    public QualityGateConfig loadGateConfig(Path path) {
        try {
            QualityGateConfig config = objectMapper.readValue(path.toFile(), QualityGateConfig.class);
            requireSupportedVersion(config.schemaVersion(), path.toString());
            return config;
        } catch (Exception e) {
            throw new IllegalArgumentException("读取质量门禁配置失败: " + path, e);
        }
    }

    public QualityGateConfig loadGateConfig(Resource resource) {
        try (var input = resource.getInputStream()) {
            QualityGateConfig config = objectMapper.readValue(input, QualityGateConfig.class);
            requireSupportedVersion(config.schemaVersion(), resource.getDescription());
            return config;
        } catch (Exception e) {
            throw new IllegalArgumentException("读取质量门禁配置失败: " + resource.getDescription(), e);
        }
    }

    public EvaluationReport loadReport(Path path) {
        try {
            EvaluationReport report = objectMapper.readValue(path.toFile(), EvaluationReport.class);
            requireSupportedVersion(report.schemaVersion(), path.toString());
            return report;
        } catch (Exception e) {
            throw new IllegalArgumentException("读取评测报告失败: " + path, e);
        }
    }

    public void writeObservations(Path path, List<EvaluationObservation> observations) {
        try {
            createParent(path);
            StringBuilder content = new StringBuilder();
            for (EvaluationObservation observation : observations) {
                content.append(objectMapper.writeValueAsString(observation)).append(System.lineSeparator());
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写入观测值失败: " + path, e);
        }
    }

    private <T> List<T> loadJsonLines(Path path, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Path jsonl : resolveJsonlFiles(path)) {
            try {
                List<String> lines = Files.readAllLines(jsonl, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        try {
                            result.add(objectMapper.readValue(line, type));
                        } catch (Exception e) {
                            throw new IllegalArgumentException("解析失败: " + jsonl + ":" + (i + 1), e);
                        }
                    }
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("读取评测数据失败: " + jsonl, e);
            }
        }
        return result;
    }

    private <T> List<T> loadJsonLines(Resource resource, Class<T> type) {
        List<T> result = new ArrayList<>();
        try (var reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            int lineNumber = 0;
            String rawLine;
            while ((rawLine = reader.readLine()) != null) {
                lineNumber++;
                String line = rawLine.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    try {
                        result.add(objectMapper.readValue(line, type));
                    } catch (Exception e) {
                        throw new IllegalArgumentException(
                                "解析失败: " + resource.getDescription() + ":" + lineNumber, e);
                    }
                }
            }
            return result;
        } catch (IOException e) {
            throw new IllegalArgumentException("读取评测数据失败: " + resource.getDescription(), e);
        }
    }

    private static List<Path> resolveJsonlFiles(Path path) {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("评测数据不存在: " + path);
        }
        if (Files.isRegularFile(path)) {
            return List.of(path);
        }
        try (var stream = Files.walk(path)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".jsonl"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
            if (files.isEmpty()) {
                throw new IllegalArgumentException("目录中没有 .jsonl 文件: " + path);
            }
            return files;
        } catch (IOException e) {
            throw new IllegalArgumentException("扫描评测数据失败: " + path, e);
        }
    }

    private static void requireSupportedVersion(String version, String identity) {
        if (!SUPPORTED_SCHEMA_VERSION.equals(version)) {
            throw new IllegalArgumentException("不支持 schemaVersion=" + version + ": " + identity);
        }
    }

    private static void createParent(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
