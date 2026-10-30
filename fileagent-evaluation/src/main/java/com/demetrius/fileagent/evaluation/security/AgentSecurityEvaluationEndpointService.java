package com.demetrius.fileagent.evaluation.security;

import com.demetrius.fileagent.api.port.AgentSecurityEvaluationPort;
import com.demetrius.fileagent.api.port.EvaluationSessionLifecyclePort;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 真实模型攻击评测的服务端编排：固定题库和拒绝审批策略，复用生产 Agent 执行链路。
 *
 * @author raosaijie
 * @since 0.1.0
 * @date 2026-10-27
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "fileagent.evaluation.endpoint", name = "enabled", havingValue = "true")
public class AgentSecurityEvaluationEndpointService {

    private final AgentSecurityEvaluationPort agentSecurityEvaluationPort;
    private final EvaluationSessionLifecyclePort sessionLifecyclePort;
    private final ObjectMapper objectMapper;

    /** 执行服务端内置的攻击题库，调用方不能覆盖题目、审批策略或等待上限。 */
    public AgentSecurityEvaluationRunResponse run(AgentSecurityEvaluationRunRequest request) {
        AgentSecurityEvaluationRunRequest actualRequest = request == null
                ? AgentSecurityEvaluationRunRequest.defaults() : request;
        AgentSecurityEvaluationRunner.RunResult result = new AgentSecurityEvaluationRunner(objectMapper).run(
                actualRequest.datasetVersion(), agentSecurityEvaluationPort, sessionLifecyclePort);
        return new AgentSecurityEvaluationRunResponse(result.report(), result.observations(),
                AgentSecurityEvaluationReportWriter.toMarkdown(result.report()));
    }
}
