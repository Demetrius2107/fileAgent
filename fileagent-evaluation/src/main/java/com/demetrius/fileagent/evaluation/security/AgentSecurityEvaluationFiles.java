package com.demetrius.fileagent.evaluation.security;

import org.springframework.core.io.Resource;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Phase 3 安全攻击题库和门禁文件读取器，单独于回答质量评测文件格式维护。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
final class AgentSecurityEvaluationFiles {

    static final String SUPPORTED_SCHEMA_VERSION = "1.0";

    private final ObjectMapper objectMapper;

    AgentSecurityEvaluationFiles(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<AgentSecurityEvaluationCase> loadCases(Resource resource) {
        if (resource == null || !resource.exists()) {
            throw new IllegalArgumentException("安全评测题库不存在");
        }
        List<AgentSecurityEvaluationCase> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try (var reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String raw;
            int lineNumber = 0;
            while ((raw = reader.readLine()) != null) {
                lineNumber++;
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                AgentSecurityEvaluationCase evaluationCase = objectMapper.readValue(line,
                        AgentSecurityEvaluationCase.class);
                requireVersion(evaluationCase.schemaVersion(), resource.getDescription() + ":" + lineNumber);
                if (!ids.add(evaluationCase.id())) {
                    throw new IllegalArgumentException("安全评测题 id 重复: " + evaluationCase.id());
                }
                cases.add(evaluationCase);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("读取安全评测题库失败: " + resource.getDescription(), e);
        }
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("安全评测题库不能为空");
        }
        return List.copyOf(cases);
    }

    AgentSecurityQualityGateConfig loadGate(Resource resource) {
        if (resource == null || !resource.exists()) {
            throw new IllegalArgumentException("安全评测门禁配置不存在");
        }
        try (var input = resource.getInputStream()) {
            AgentSecurityQualityGateConfig config = objectMapper.readValue(input,
                    AgentSecurityQualityGateConfig.class);
            requireVersion(config.schemaVersion(), resource.getDescription());
            return config;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("读取安全评测门禁配置失败: " + resource.getDescription(), e);
        }
    }

    private static void requireVersion(String schemaVersion, String source) {
        if (!SUPPORTED_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("不支持安全评测 schemaVersion=" + schemaVersion + ": " + source);
        }
    }
}
