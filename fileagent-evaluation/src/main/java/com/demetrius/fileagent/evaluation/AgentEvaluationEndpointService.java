package com.demetrius.fileagent.evaluation;

import com.demetrius.fileagent.api.port.AgentAnswerEvaluationPort;
import com.demetrius.fileagent.api.port.RagAnswerJudgePort;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 在已部署实例内执行真实 AgentScope Runtime 的 Agent 端到端评测。
 *
 * @author raosaijie
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "fileagent.evaluation.endpoint", name = "enabled", havingValue = "true")
public class AgentEvaluationEndpointService {

    private final AgentAnswerEvaluationPort agentAnswerEvaluationPort;
    private final RagAnswerJudgePort ragAnswerJudgePort;
    private final ObjectMapper objectMapper;
    private final ResourcePatternResolver resourcePatternResolver;

    public AgentEvaluationRunResponse run(AgentEvaluationRunRequest request) {
        AgentEvaluationRunRequest actualRequest = request == null ? AgentEvaluationRunRequest.defaults() : request;
        AgentEvaluationRunner runner = new AgentEvaluationRunner(ragAnswerJudgePort,
                new EvaluationFiles(objectMapper), resourcePatternResolver);
        AgentEvaluationRunner.RunResult result = runner.runWithObservations(
                actualRequest.datasetVersion(), agentAnswerEvaluationPort);
        QualityGateConfig gateConfig = new EvaluationFiles(objectMapper).loadGateConfig(resource(
                "classpath:evaluation/" + actualRequest.datasetVersion() + "/gate.json"));
        AgentEvaluationReport report = result.report().withGate(
                new AgentQualityGateEvaluator().evaluate(result.report(), gateConfig, actualRequest.baseline()));
        return new AgentEvaluationRunResponse(report, result.observations(),
                AgentEvaluationReportWriter.toMarkdown(report));
    }

    private Resource resource(String location) {
        Resource resource = resourcePatternResolver.getResource(location);
        if (!resource.exists()) {
            throw new IllegalArgumentException("评测资源不存在: " + location);
        }
        return resource;
    }
}
